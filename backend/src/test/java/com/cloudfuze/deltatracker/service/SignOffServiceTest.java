package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers SignOffService.decline()'s rewritten behavior: a decline at any step now ends the cycle and
 * rolls the combination over to a fresh one (DeltaCycleService.recordDeclineAndRollOver), rather than
 * bouncing the chain back one step for rework on the same submission. See
 * .claude/rules/testing-standard.md -- this is priority #1 (SignOffService).
 */
@ExtendWith(MockitoExtension.class)
class SignOffServiceTest {

    @Mock
    private SignOffRepository signOffRepository;
    @Mock
    private WorkspaceCombinationService combinationService;
    @Mock
    private EmailService emailService;
    @Mock
    private AppUserService appUserService;
    @Mock
    private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock
    private TicketService ticketService;
    @Mock
    private DeltaCycleService deltaCycleService;
    @Mock
    private TeamService teamService;

    private SignOffService signOffService;

    private WorkspaceCombination combination;
    private SignOff migrationLeadSignOff;
    private SignOff devLeadSignOff;
    private SignOff qaLeadSignOff;

    @BeforeEach
    void setUp() {
        signOffService = new SignOffService(signOffRepository, combinationService, emailService, appUserService,
                preCheckSubmissionRepository, ticketService, deltaCycleService, teamService);

        Project project = new Project("Acme", "creator@cloudfuze.com", "manager@cloudfuze.com", null);
        Server server = new Server("box.com");
        server.setId(1L);
        server.setProject(project);

        combination = new WorkspaceCombination(server, "Box to OneDrive");
        combination.setId(10L);

        migrationLeadSignOff = new SignOff(combination, SignOffRole.MIGRATION_LEAD, "manager@cloudfuze.com");
        migrationLeadSignOff.setStatus(SignOffStatus.APPROVED);
        migrationLeadSignOff.setApprovedBy("manager@cloudfuze.com");
        migrationLeadSignOff.setApprovedAt(LocalDateTime.now().minusHours(1));

        devLeadSignOff = new SignOff(combination, SignOffRole.DEV_LEAD, "Any Dev Lead");
        devLeadSignOff.setStatus(SignOffStatus.PENDING);

        qaLeadSignOff = new SignOff(combination, SignOffRole.QA_LEAD, "Any QA Lead");
        qaLeadSignOff.setStatus(SignOffStatus.PENDING);

        // lenient(): decline()-based tests use this; the listApprovals()-based ones below never call
        // combinationService.findOrThrow at all.
        lenient().when(combinationService.findOrThrow(10L)).thenReturn(combination);
        // Only reached on the success path (toApprovalDto/computeCombinationStats) -- the
        // missing-reason test throws before any of this, so these are lenient rather than shared.
        lenient().when(combinationService.pairCount(any())).thenReturn(3);
        lenient().when(ticketService.countOpenForCombination(10L)).thenReturn(0L);
        lenient().when(preCheckSubmissionRepository.findByCombinationId(10L)).thenReturn(Optional.empty());
        lenient().when(signOffRepository.save(any(SignOff.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void declineWithoutReasonIsRejected() {
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));

        assertThatThrownBy(() -> signOffService.decline(10L, SignOffRole.DEV_LEAD, "dev@cloudfuze.com", "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("A reason is required");

        verify(deltaCycleService, never()).recordDeclineAndRollOver(any(), any(), any(), any(), any());
    }

    // The core behavior change: a Dev Lead decline used to reset the already-approved Migration
    // Manager row back to PENDING so the same submission bounced back for rework. It no longer does --
    // the cycle ends and rolls over instead, so the Migration Manager's row is never touched.
    @Test
    void devLeadDeclineDoesNotBounceMigrationManagerBackToPending() {
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("dev@cloudfuze.com")).thenReturn(false);
        when(appUserService.roleOf("dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.DEV_LEAD));
        when(appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of("engineer@cloudfuze.com"));

        SignOffApprovalDto result = signOffService.decline(10L, SignOffRole.DEV_LEAD, "dev@cloudfuze.com",
                "Missing evidence");

        assertThat(result).isNotNull();
        assertThat(devLeadSignOff.getStatus()).isEqualTo(SignOffStatus.DECLINED);
        assertThat(devLeadSignOff.getDeclineReason()).isEqualTo("Missing evidence");
        // The Migration Manager row is never re-saved as PENDING -- only the declining role's own row
        // is ever written by decline() now.
        verify(signOffRepository, times(1)).save(any(SignOff.class));
        assertThat(migrationLeadSignOff.getStatus()).isEqualTo(SignOffStatus.APPROVED);

        verify(deltaCycleService).recordDeclineAndRollOver(eq(combination), eq(SignOffRole.DEV_LEAD),
                eq("dev@cloudfuze.com"), eq("Missing evidence"), any(LocalDateTime.class));

        // Every decline notifies the Migration Engineers pool -- previously only a Dev/QA decline
        // notified anyone at all, and never the engineers who'd actually redo the work.
        verify(emailService).notifyMigrationEngineersPreCheckDeclined(anyString(), anyString(), anyString(),
                eq("Dev Lead"), eq("dev@cloudfuze.com"), eq("Missing evidence"), eq(List.of("engineer@cloudfuze.com")));
        // A Dev Lead decline (unlike a Migration Manager's own) also tells the manager, for visibility.
        verify(emailService).notifyMigrationManagerApprovalDeclined(anyString(), anyString(), anyString(),
                eq(3), eq("Dev Lead"), eq("dev@cloudfuze.com"), eq("Missing evidence"), eq("manager@cloudfuze.com"));
    }

    // A Migration Manager decline used to sit permanently blocked with no email to anyone. It now
    // rolls over just like every other role, and skips the (redundant) manager email since they
    // already know -- they just declined it themselves.
    @Test
    void migrationLeadDeclineRollsOverAndSkipsManagerEmail() {
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.MIGRATION_LEAD))
                .thenReturn(Optional.of(migrationLeadSignOff));
        migrationLeadSignOff.setStatus(SignOffStatus.PENDING);
        migrationLeadSignOff.setApprovedBy(null);
        migrationLeadSignOff.setApprovedAt(null);
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("manager@cloudfuze.com")).thenReturn(false);
        when(appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER))
                .thenReturn(List.of("engineer@cloudfuze.com"));

        signOffService.decline(10L, SignOffRole.MIGRATION_LEAD, "manager@cloudfuze.com", "Wrong workspace pairs");

        verify(deltaCycleService).recordDeclineAndRollOver(eq(combination), eq(SignOffRole.MIGRATION_LEAD),
                eq("manager@cloudfuze.com"), eq("Wrong workspace pairs"), any(LocalDateTime.class));
        verify(emailService).notifyMigrationEngineersPreCheckDeclined(anyString(), anyString(), anyString(),
                eq("Migration Manager"), eq("manager@cloudfuze.com"), eq("Wrong workspace pairs"),
                eq(List.of("engineer@cloudfuze.com")));
        verify(emailService, never()).notifyMigrationManagerApprovalDeclined(anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- listApprovals: a declined combination stays visible (status-only) until resubmitted -------
    // The rollover deletes the live sign-off chain in the same transaction as the decline, so without
    // this a combination would vanish from Approvals the instant it's declined. It should instead stay
    // there, reading "Declined", until either resubmitted (a fresh live chain takes over) or the
    // combination/server/project is deleted.

    private com.cloudfuze.deltatracker.entity.DeltaCycle declinedCycle(int cycleNumber) {
        com.cloudfuze.deltatracker.entity.DeltaCycle cycle = new com.cloudfuze.deltatracker.entity.DeltaCycle(
                combination, cycleNumber, com.cloudfuze.deltatracker.entity.DeltaType.PRE_DELTA);
        cycle.setId(100L + cycleNumber);
        // combinationId is a shadow (insertable=false/updatable=false) column Hibernate populates from
        // the FK on a real row load -- a bare `new DeltaCycle(...)` in a unit test needs it set by
        // hand, or SignOffService's "does this combination already have a live chain?" filter (which
        // keys off this field) silently never matches.
        cycle.setCombinationId(combination.getId());
        cycle.setStatus(com.cloudfuze.deltatracker.entity.DeltaCycleStatus.DECLINED);
        cycle.setDeclinedByRole(SignOffRole.DEV_LEAD);
        cycle.setDeclinedBy("dev@cloudfuze.com");
        cycle.setDeclineReason("Missing evidence");
        cycle.setSubmittedBy("engineer@cloudfuze.com");
        cycle.setSubmittedAt(LocalDateTime.now().minusHours(2));
        return cycle;
    }

    @Test
    void aDeclinedCombinationWithNoLiveChainStillAppearsInApprovalsAsDeclined() {
        when(signOffRepository.findAll()).thenReturn(List.of());
        when(deltaCycleService.findDeclinedAwaitingResubmission()).thenReturn(List.of(declinedCycle(1)));

        List<SignOffApprovalDto> approvals = signOffService.listApprovals(null, AppUserRole.ADMIN);

        assertThat(approvals).singleElement().satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(SignOffStatus.DECLINED);
            assertThat(dto.getCombinationId()).isEqualTo(10L);
            assertThat(dto.getDeclineReason()).isEqualTo("Missing evidence");
            assertThat(dto.getDeclinedByRoleLabel()).isEqualTo("Dev Lead");
            assertThat(dto.getDeclinedBy()).isEqualTo("dev@cloudfuze.com");
            // Frozen at the declined cycle's own numbering, NOT whatever the combination's live
            // (blank, not-yet-resubmitted) currentCycleNumber has since advanced to.
            assertThat(dto.getCycleNumber()).isEqualTo(1);
            assertThat(dto.getDeltaLabel()).isEqualTo("Pre-Delta 1.1");
            // Status-only: nothing to act on from this list.
            assertThat(dto.isCanAct()).isFalse();
        });
    }

    @Test
    void aCombinationAlreadyResubmittedIsNotAlsoShownAsDeclined() {
        // A live chain exists again (resubmitted) -- the declined-awaiting-resubmission entry for the
        // SAME combination must not also appear, or it would double-list the one combination.
        when(signOffRepository.findAll()).thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(deltaCycleService.findDeclinedAwaitingResubmission()).thenReturn(List.of(declinedCycle(1)));

        List<SignOffApprovalDto> approvals = signOffService.listApprovals(null, AppUserRole.ADMIN);

        assertThat(approvals).extracting(SignOffApprovalDto::getStatus)
                .doesNotContain(SignOffStatus.DECLINED);
    }

    @Test
    void anEngineerOutsideTheProjectDoesNotSeeADeclinedCombinationEitherWayViaTeamService() {
        when(signOffRepository.findAll()).thenReturn(List.of());
        when(deltaCycleService.findDeclinedAwaitingResubmission()).thenReturn(List.of(declinedCycle(1)));
        when(teamService.isCurrentlyOnManagersTeam("manager@cloudfuze.com", "outsider@cloudfuze.com"))
                .thenReturn(false);

        List<SignOffApprovalDto> approvals =
                signOffService.listApprovals("outsider@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER);

        assertThat(approvals).isEmpty();
    }
}
