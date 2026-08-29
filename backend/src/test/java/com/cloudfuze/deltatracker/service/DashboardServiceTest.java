package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DashboardServerDto;
import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link DashboardService} -- specifically that its figures are scoped to the caller.
 *
 * <p>Before this, {@code getSummary()} counted the whole database whoever asked. A Migration Manager
 * saw a Pending Approvals number covering every other manager's work, clicked it, and landed on an
 * Approvals page showing only their own rows. The tiles disagreed with every other screen in the app,
 * and the size of somebody else's backlog was readable off them.
 *
 * <p>The scoping rule itself is deliberately NOT re-tested here -- it belongs to
 * {@code ProjectService.isVisible} and is exercised there. What these tests pin down is that
 * DashboardService actually asks, and applies the answer to every rollup rather than to some of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private SignOffRepository signOffRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private DeltaCycleRepository deltaCycleRepository;
    @Mock private ProjectService projectService;

    private DashboardService service;

    private static final String MANAGER = "harika.velidi@cloudfuze.com";

    // Two projects, one visible to our manager and one not. Everything below hangs off these.
    private final Project mine = project(1L, "Acme Migration");
    private final Project theirs = project(2L, "Someone Else's Migration");

    private final Server myServer = server(10L, "https://acme.example.com", mine, ProductType.MESSAGE);
    private final Server theirServer = server(20L, "https://other.example.com", theirs, ProductType.EMAIL);

    @BeforeEach
    void setUp() {
        service = new DashboardService(workspaceCombinationRepository, signOffRepository,
                serverRepository, deltaCycleRepository, projectService);
        when(serverRepository.findAll()).thenReturn(List.of(myServer, theirServer));
        when(workspaceCombinationRepository.findAll()).thenReturn(List.of());
        when(signOffRepository.findAll()).thenReturn(List.of());
        when(deltaCycleRepository.findAll()).thenReturn(List.of());
    }

    private static Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static Server server(Long id, String name, Project project, ProductType type) {
        Server s = new Server();
        s.setId(id);
        s.setName(name);
        s.setProject(project);
        s.setProductType(type);
        s.setStatus(PairStatus.PENDING);
        return s;
    }

    private static WorkspaceCombination combination(Long id, String name, Server server, PairStatus status) {
        WorkspaceCombination c = new WorkspaceCombination();
        c.setId(id);
        c.setName(name);
        c.setServer(server);
        c.setStatus(status);
        return c;
    }

    private static SignOff signOff(WorkspaceCombination combination, SignOffRole role, SignOffStatus status) {
        SignOff so = new SignOff();
        so.setCombination(combination);
        so.setRole(role);
        so.setStatus(status);
        return so;
    }

    /** Only `mine` is visible to this caller. */
    private void managerSeesOnlyTheirOwnProject() {
        when(projectService.visibleProjects(eq(MANAGER), any())).thenReturn(List.of(mine));
    }

    // --- scoping ----------------------------------------------------------------------------------

    @Test
    void countsOnlyServersOnProjectsTheCallerCanSee() {
        managerSeesOnlyTheirOwnProject();

        DashboardSummaryDto summary = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(summary.getServers()).extracting(DashboardServerDto::getServerName)
                .containsExactly("https://acme.example.com");
    }

    @Test
    void doesNotCountAnotherManagersPendingApproval() {
        // The number that was most visibly wrong: a manager's dashboard reported approvals waiting on
        // chains they cannot open, so the tile never matched the Approvals page it links to.
        managerSeesOnlyTheirOwnProject();
        WorkspaceCombination ours = combination(100L, "Teams to Slack", myServer, PairStatus.IN_PROGRESS);
        WorkspaceCombination theirsCombo = combination(200L, "Gmail to Gmail", theirServer, PairStatus.IN_PROGRESS);
        when(workspaceCombinationRepository.findAll()).thenReturn(List.of(ours, theirsCombo));
        when(signOffRepository.findAll()).thenReturn(List.of(
                signOff(ours, SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING),
                signOff(theirsCombo, SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING)));

        DashboardSummaryDto summary = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(summary.getTotalApprovalRequests()).isEqualTo(1);
        assertThat(summary.getMigrationManagerApprovalsPending()).isEqualTo(1);
    }

    @Test
    void anAdminSeesEverything() {
        // ADMIN, DEV_LEAD and QA_LEAD are unrestricted by ProjectService.isVisible, and this must add
        // no rule of its own on top of that.
        when(projectService.visibleProjects(any(), any())).thenReturn(List.of(mine, theirs));

        DashboardSummaryDto summary = service.getSummary("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(summary.getServers()).hasSize(2);
    }

    @Test
    void withAuthOffEverythingStaysVisible() {
        // callerEmail == null is how the app behaves when AZURE_CLIENT_ID is blank. ProjectService
        // returns everything in that case, and the dashboard must not be stricter than the pages.
        when(projectService.visibleProjects(eq(null), any())).thenReturn(List.of(mine, theirs));

        DashboardSummaryDto summary = service.getSummary(null, null);

        assertThat(summary.getServers()).hasSize(2);
    }

    @Test
    void ignoresAServerThatBelongsToNoProject() {
        // Unreachable from any project page, so counting it would put a number on screen that no click
        // can explain.
        managerSeesOnlyTheirOwnProject();
        when(serverRepository.findAll()).thenReturn(List.of(myServer, server(30L, "orphan", null, ProductType.CONTENT)));

        DashboardSummaryDto summary = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(summary.getServers()).hasSize(1);
    }

    // --- the rows behind the tiles ----------------------------------------------------------------

    @Test
    void aServerRowCarriesItsProjectAndProductType() {
        // A server name alone does not identify it -- the same name recurs across projects.
        managerSeesOnlyTheirOwnProject();

        DashboardServerDto row = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER).getServers().get(0);

        assertThat(row.getProjectName()).isEqualTo("Acme Migration");
        assertThat(row.getProjectId()).isEqualTo(1L);
        assertThat(row.getProductType()).isEqualTo(ProductType.MESSAGE);
    }

    @Test
    void deltaReadyMarksTheServerAndNamesOnlyTheReadyCombinations() {
        // "Server X is Delta Ready" is ambiguous when only some of its combinations are, so the row
        // names them. Each combination runs its own independent chain.
        managerSeesOnlyTheirOwnProject();
        myServer.setStatus(PairStatus.DELTA_READY);
        when(workspaceCombinationRepository.findAll()).thenReturn(List.of(
                combination(100L, "Teams to Slack", myServer, PairStatus.DELTA_READY),
                combination(101L, "Drive to Drive", myServer, PairStatus.IN_PROGRESS)));

        DashboardServerDto row = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER).getServers().get(0);

        assertThat(row.isDeltaReady()).isTrue();
        assertThat(row.getDeltaReadyCombinations()).containsExactly("Teams to Slack");
    }

    @Test
    void aServerThatIsNotDeltaReadyIsStillListedUnderServers() {
        // The Servers popup lists every server; only the Delta Ready popup filters. One list backs
        // both tiles, so this is what stops the Servers count silently becoming a ready-count.
        managerSeesOnlyTheirOwnProject();

        DashboardServerDto row = service.getSummary(MANAGER, AppUserRole.MIGRATION_MANAGER).getServers().get(0);

        assertThat(row.isDeltaReady()).isFalse();
        assertThat(row.getDeltaReadyCombinations()).isEmpty();
    }

    @Test
    void serverRowsAreOrderedByProjectThenServer() {
        // A long list should read as projects rather than as an undifferentiated wall of names.
        when(projectService.visibleProjects(any(), any())).thenReturn(List.of(mine, theirs));
        Server second = server(11L, "https://acme-2.example.com", mine, ProductType.CONTENT);
        when(serverRepository.findAll()).thenReturn(List.of(theirServer, second, myServer));

        DashboardSummaryDto summary = service.getSummary("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(summary.getServers()).extracting(DashboardServerDto::getServerName)
                .containsExactly("https://acme-2.example.com", "https://acme.example.com",
                        "https://other.example.com");
    }
}
