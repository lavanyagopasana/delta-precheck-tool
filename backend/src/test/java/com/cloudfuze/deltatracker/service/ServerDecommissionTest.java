package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-server decommission flow on {@link ServerService}. Pure Mockito.
 *
 * <p>The rule that matters: eligibility keys off {@code finalDeltaCompletedAt}, never
 * {@code deltaFinishedAt}. Now that a combination runs several pre-deltas and each one stamps
 * (then clears) {@code deltaFinishedAt}, using it would call a server decommissionable after its
 * first pre-delta -- long before the migration was actually done.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServerDecommissionTest {

    private static final Long SID = 10L;
    private static final String ADMIN = "admin@cloudfuze.com";
    private static final String MANAGER = "mgr@cloudfuze.com";
    private static final String ENGINEER = "eng@cloudfuze.com";

    @Mock private ServerRepository serverRepository;
    @Mock private WorkspacePairRepository workspacePairRepository;
    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private TicketService ticketService;
    @Mock private ProjectRepository projectRepository;
    @Mock private DeltaCycleRepository deltaCycleRepository;
    @Mock private AppUserService appUserService;
    @Mock private ServerPurgeService serverPurgeService;
    @Mock private TeamService teamService;
    @Mock private ChangeLogService changeLogService;

    private ServerService service;
    private Server server;

    @BeforeEach
    void setUp() {
        service = new ServerService(serverRepository, workspacePairRepository, workspaceCombinationRepository,
                preCheckSubmissionRepository, ticketService, projectRepository, deltaCycleRepository, appUserService,
                serverPurgeService, teamService, changeLogService);

        server = new Server("SRV-1");
        server.setId(SID);

        when(serverRepository.findById(SID)).thenReturn(Optional.of(server));
        when(serverRepository.save(any(Server.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workspacePairRepository.findByServerId(SID)).thenReturn(List.of());
        when(preCheckSubmissionRepository.findByCombinationIdIn(any())).thenReturn(List.of());
        when(deltaCycleRepository.findByCombinationIdIn(any())).thenReturn(List.of());
        when(ticketService.countOpenForServer(anyLong())).thenReturn(0L);
        // canEditProjectData, not isAdmin: delete/decommission is now admins AND Migration
        // Managers, and the service asks that one predicate rather than checking a role itself.
        when(appUserService.canEditProjectData(ADMIN)).thenReturn(true);
        when(appUserService.canEditProjectData(MANAGER)).thenReturn(true);
        when(appUserService.canEditProjectData(ENGINEER)).thenReturn(false);
    }

    private WorkspaceCombination combination(Long id, boolean finalDeltaDone) {
        WorkspaceCombination c = new WorkspaceCombination(server, "Combo " + id);
        c.setId(id);
        if (finalDeltaDone) {
            c.setFinalDeltaCompletedAt(LocalDateTime.of(2026, 3, 1, 12, 0));
            c.setFinalDeltaCompletedBy(ENGINEER);
        }
        return c;
    }

    private void givenCombinations(WorkspaceCombination... combinations) {
        when(workspaceCombinationRepository.findByServerId(SID)).thenReturn(List.of(combinations));
    }

    // ---- eligibility ----

    @Test
    void readyOnlyWhenEveryCombinationHasFinishedItsFinalDelta() {
        givenCombinations(combination(1L, true), combination(2L, true));

        assertThat(service.isDecommissionReady(server)).isTrue();
    }

    @Test
    void notReadyWhileAnyCombinationStillHasWorkLeft() {
        givenCombinations(combination(1L, true), combination(2L, false));

        assertThat(service.isDecommissionReady(server)).isFalse();
    }

    @Test
    void notReadyWhenAPreDeltaFinishedButTheFinalDeltaHasNot() {
        // The regression this guards: deltaFinishedAt is set by every intermediate pre-delta, so keying
        // eligibility off it would report this server ready when nothing is actually finished.
        WorkspaceCombination midFlight = combination(1L, false);
        midFlight.setDeltaFinishedAt(LocalDateTime.of(2026, 3, 1, 12, 0));
        givenCombinations(midFlight);

        assertThat(service.isDecommissionReady(server)).isFalse();
    }

    @Test
    void aServerWithNoCombinationsIsNeverReady() {
        // An empty server has nothing to migrate; calling it decommissionable would be misleading.
        givenCombinations();

        assertThat(service.isDecommissionReady(server)).isFalse();
    }

    @Test
    void aStaleDecommissionedFlagDoesNotBlockReadiness() {
        // Decommissioning erases the server now, so a live row can only carry this flag if it was marked
        // under the previous marker-only behaviour. Treating it as "not ready" would leave those rows
        // stuck forever: flagged, still present, with no action available to clear them out.
        givenCombinations(combination(1L, true));
        server.setDecommissionedAt(LocalDateTime.of(2026, 3, 2, 9, 0));
        server.setDecommissionedBy(ADMIN);

        assertThat(service.getReadiness(SID).isDecommissionReady()).isTrue();
    }

    // ---- decommission (erases the server -- see ServerService.decommission) ----

    @Test
    void adminDecommissioningAReadyServerErasesIt() {
        givenCombinations(combination(1L, true));

        service.decommission(SID, ADMIN);

        verify(serverPurgeService).purge(server);
    }

    @Test
    void nonAdminCannotDecommission() {
        givenCombinations(combination(1L, true));

        assertThatThrownBy(() -> service.decommission(SID, ENGINEER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(serverPurgeService, never()).purge(any());
    }

    @Test
    void decommissionRejectedWhileAnyFinalDeltaIsOutstanding() {
        // The guard that stops this becoming a way to delete in-flight migration work.
        givenCombinations(combination(1L, true), combination(2L, false));

        assertThatThrownBy(() -> service.decommission(SID, ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Final Delta");
        verify(serverPurgeService, never()).purge(any());
    }

    @Test
    void decommissionRejectedForAServerWithNoCombinations() {
        // Nothing has been migrated, so there is no finished work to close out -- and erasing an empty
        // server through this path would be a delete dressed up as a decommission.
        givenCombinations();

        assertThatThrownBy(() -> service.decommission(SID, ADMIN))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(serverPurgeService, never()).purge(any());
    }

    @Test
    void aStaleDecommissionedFlagDoesNotBlockTheErase() {
        givenCombinations(combination(1L, true));
        server.setDecommissionedAt(LocalDateTime.of(2026, 3, 2, 9, 0));

        service.decommission(SID, ADMIN);

        verify(serverPurgeService).purge(server);
    }

    @Test
    void authNotConfiguredStillAllowsTheAction() {
        // A null caller email is SecurityConfig's fully-open local-dev mode, which the whole app
        // deliberately degrades to -- decommissioning must not be the one thing impossible offline.
        givenCombinations(combination(1L, true));

        service.decommission(SID, null);

        verify(serverPurgeService).purge(server);
    }

    // ---- deleteServer (admin-only erase at any time -- see ServerService.deleteServer) ----

    @Test
    void adminCanDeleteServerWithNoCombinations() {
        givenCombinations();

        service.deleteServer(SID, ADMIN);

        verify(serverPurgeService).purge(server);
    }

    @Test
    void aMigrationManagerCanDeleteAServerToo() {
        // The change that opened this up: managers own delivery for their projects, so the
        // destructive project actions are theirs as well as an admin's.
        givenCombinations();

        service.deleteServer(SID, MANAGER);

        verify(serverPurgeService).purge(server);
    }

    @Test
    void anEngineerStillCannotDeleteAServer() {
        // The other half of the same rule -- widening it to managers must not widen it to everyone.
        givenCombinations();

        assertThatThrownBy(() -> service.deleteServer(SID, ENGINEER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin or a Migration Manager");
        verify(serverPurgeService, never()).purge(any());
    }

    @Test
    void adminCanDeleteServerWhileFinalDeltasAreStillOutstanding() {
        givenCombinations(combination(1L, true), combination(2L, false));

        service.deleteServer(SID, ADMIN);

        verify(serverPurgeService).purge(server);
    }

    @Test
    void nonAdminCannotDeleteServer() {
        givenCombinations(combination(1L, true));

        assertThatThrownBy(() -> service.deleteServer(SID, ENGINEER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(serverPurgeService, never()).purge(any());
    }

    @Test
    void authNotConfiguredStillAllowsDelete() {
        givenCombinations(combination(1L, false));

        service.deleteServer(SID, null);

        verify(serverPurgeService).purge(server);
    }

    // ---- readiness rollup ----

    @Test
    void readinessReportsHowManyCombinationsHaveFinishedTheirFinalDelta() {
        givenCombinations(combination(1L, true), combination(2L, false), combination(3L, true));

        ServerReadinessDto dto = service.getReadiness(SID);

        assertThat(dto.getFinalDeltaCompleteCount()).isEqualTo(2);
        assertThat(dto.isDecommissionReady()).isFalse();
        assertThat(dto.getCombinations()).extracting(c -> c.isFinalDeltaComplete())
                .containsExactly(true, false, true);
    }
}
