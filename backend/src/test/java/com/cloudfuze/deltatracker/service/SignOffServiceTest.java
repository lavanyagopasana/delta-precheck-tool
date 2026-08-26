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

    private SignOffService signOffService;

    private WorkspaceCombination combination;
    private SignOff migrationLeadSignOff;
    private SignOff devLeadSignOff;
    private SignOff qaLeadSignOff;

    @BeforeEach
    void setUp() {
        signOffService = new SignOffService(signOffRepository, combinationService, emailService, appUserService,
                preCheckSubmissionRepository, ticketService, deltaCycleService);

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

        when(combinationService.findOrThrow(10L)).thenReturn(combination);
        // Only reached on the success path (toApprovalDto/computeCombinationStats) -- the
        // missing-reason test throws before any of this, so these are lenient rather than shared.
        lenient().when(combinationService.pairCount(any())).thenReturn(3);
        lenient().when(ticketService.countOpenForCombination(10L)).thenReturn(0L);
        lenient().when(preCheckSubmissionRepository.findByCombinationId(10L)).thenReturn(Optional.empty());
        lenient().when(signOffRepository.save(any(SignOff.class))).thenAnswer(inv -> inv.getArgument(0));
        // finalizeDelta saves the combination and then recomputes status on the RETURNED instance,
        // so this has to echo the argument back rather than default to null.
        lenient().when(combinationService.save(any(WorkspaceCombination.class))).thenAnswer(inv -> inv.getArgument(0));
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

    // ---- Per-project Dev Lead / QA Lead assignment -------------------------------------------
    //
    // Before this, ANY holder of DEV_LEAD could approve any project's Dev Lead step. A project can
    // now name one, and then only that person may act. The unassigned case must keep working, or
    // every project created before the feature would have a chain nobody can move.

    private Project project() {
        return combination.getServer().getProject();
    }

    @Test
    void anAssignedDevLeadCanApproveTheirOwnProject() {
        project().setDevLeadEmail("assigned.dev@cloudfuze.com");
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("assigned.dev@cloudfuze.com")).thenReturn(false);
        when(appUserService.roleOf("assigned.dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        signOffService.approve(10L, SignOffRole.DEV_LEAD, "assigned.dev@cloudfuze.com", Boolean.FALSE);

        assertThat(devLeadSignOff.getStatus()).isEqualTo(SignOffStatus.APPROVED);
    }

    @Test
    void aDifferentDevLeadCannotApproveAProjectAssignedToSomeoneElse() {
        // The actual point of the feature: holding the role is no longer enough once a project names
        // a specific lead.
        project().setDevLeadEmail("assigned.dev@cloudfuze.com");
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        // Chain stubbed so requireTurn PASSES -- otherwise the rejection below would come from
        // turn-taking and this test would prove nothing about eligibility.
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("other.dev@cloudfuze.com")).thenReturn(false);
        when(appUserService.roleOf("other.dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        assertThatThrownBy(() -> signOffService.approve(10L, SignOffRole.DEV_LEAD, "other.dev@cloudfuze.com", Boolean.FALSE))
                .isInstanceOf(ApiException.class);

        assertThat(devLeadSignOff.getStatus()).isEqualTo(SignOffStatus.PENDING);
    }

    @Test
    void anyDevLeadCanStillApproveWhenTheProjectAssignsNobody() {
        // Regression guard for every project that predates lead assignment: devLeadEmail is null, so
        // the step must stay open to the whole DEV_LEAD pool rather than becoming unactionable.
        assertThat(project().getDevLeadEmail()).isNull();
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("any.dev@cloudfuze.com")).thenReturn(false);
        when(appUserService.roleOf("any.dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        signOffService.approve(10L, SignOffRole.DEV_LEAD, "any.dev@cloudfuze.com", Boolean.FALSE);

        assertThat(devLeadSignOff.getStatus()).isEqualTo(SignOffStatus.APPROVED);
    }

    @Test
    void someoneNamedByTheProjectButNoLongerADevLeadIsRejected() {
        // Assignment does not outrank the role check: being moved off DEV_LEAD has to take effect
        // even while an old project still names you.
        project().setDevLeadEmail("former.dev@cloudfuze.com");
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("former.dev@cloudfuze.com")).thenReturn(false);
        when(appUserService.roleOf("former.dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));

        assertThatThrownBy(() -> signOffService.approve(10L, SignOffRole.DEV_LEAD, "former.dev@cloudfuze.com", Boolean.FALSE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void adminStillOverridesAnAssignedLead() {
        // The existing admin override is unchanged -- it is the unblock path when an assigned lead
        // becomes unavailable, which assignment makes MORE likely, not less.
        project().setDevLeadEmail("assigned.dev@cloudfuze.com");
        when(signOffRepository.findByCombinationIdAndRole(10L, SignOffRole.DEV_LEAD))
                .thenReturn(Optional.of(devLeadSignOff));
        when(signOffRepository.findByCombinationId(10L))
                .thenReturn(List.of(migrationLeadSignOff, devLeadSignOff, qaLeadSignOff));
        when(appUserService.isAdmin("admin@cloudfuze.com")).thenReturn(true);

        signOffService.approve(10L, SignOffRole.DEV_LEAD, "admin@cloudfuze.com", Boolean.FALSE);

        assertThat(devLeadSignOff.getStatus()).isEqualTo(SignOffStatus.APPROVED);
    }
}
