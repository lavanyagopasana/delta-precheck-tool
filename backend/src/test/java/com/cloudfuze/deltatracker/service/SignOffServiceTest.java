package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SignOffService} -- the most business-rule-dense class in the app. Pure
 * Mockito; all repositories and collaborators are mocked. Covers the fixed approval sequence,
 * turn-taking, eligibility, the Dev-Lead-skips-QA branch, decline bounce-back, chain create/teardown,
 * and specifically the guard that an already-approved role cannot be re-approved (double-approval).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignOffServiceTest {

    private static final Long SID = 1L;
    private static final String MM_EMAIL = "mgr@cloudfuze.com";
    private static final String DEV_EMAIL = "dev@cloudfuze.com";
    private static final String QA_EMAIL = "qa@cloudfuze.com";

    @Mock private SignOffRepository signOffRepository;
    @Mock private ServerService serverService;
    @Mock private EmailService emailService;
    @Mock private AppUserService appUserService;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private WorkspacePairRepository workspacePairRepository;
    @Mock private TicketService ticketService;

    private SignOffService service;
    private Server server;

    @BeforeEach
    void setUp() {
        service = new SignOffService(signOffRepository, serverService, emailService, appUserService,
                preCheckSubmissionRepository, workspacePairRepository, ticketService);
        Project project = new Project("Alpha", ProductType.MESSAGE, "eng@cloudfuze.com", MM_EMAIL, null);
        server = new Server("SRV-1");
        server.setId(SID);
        server.setProject(project);

        when(serverService.findOrThrow(SID)).thenReturn(server);
        when(serverService.save(any(Server.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workspacePairRepository.findByServerId(anyLong())).thenReturn(List.of());
        when(preCheckSubmissionRepository.findByServerId(anyLong())).thenReturn(Optional.empty());
        when(ticketService.countOpenForServer(anyLong())).thenReturn(0L);
        when(appUserService.isAdmin(anyString())).thenReturn(false);
        when(appUserService.emailsForRole(any())).thenReturn(List.of());
    }

    private SignOff row(SignOffRole role, SignOffStatus status) {
        SignOff s = new SignOff(server, role, role.label());
        s.setStatus(status);
        s.setSignedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        return s;
    }

    private void stubChain(SignOff mm, SignOff dev, SignOff qa) {
        List<SignOff> chain = new ArrayList<>(List.of(mm, dev, qa));
        when(signOffRepository.findByServerId(SID)).thenReturn(chain);
        when(signOffRepository.findByServerIdAndRole(SID, SignOffRole.MIGRATION_LEAD)).thenReturn(Optional.of(mm));
        when(signOffRepository.findByServerIdAndRole(SID, SignOffRole.DEV_LEAD)).thenReturn(Optional.of(dev));
        when(signOffRepository.findByServerIdAndRole(SID, SignOffRole.QA_LEAD)).thenReturn(Optional.of(qa));
    }

    // ---- approve: happy path & sequence ----

    @Test
    void migrationLeadApprovesWhenItIsTheirTurn() {
        SignOff mm = row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING);
        stubChain(mm, row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING), row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));

        SignOffApprovalDto dto = service.approve(SID, SignOffRole.MIGRATION_LEAD, MM_EMAIL, null);

        assertThat(mm.getStatus()).isEqualTo(SignOffStatus.APPROVED);
        assertThat(mm.getApprovedBy()).isEqualTo(MM_EMAIL);
        assertThat(dto.getStatus()).isEqualTo(SignOffStatus.APPROVED);
        verify(signOffRepository).save(mm);
        // Next approver (Dev Lead) is notified.
        verify(emailService).notifyApprovalRequired(eq("Dev Lead"), any(), any(), anyInt(), any(), any());
        assertThat(server.getDeltaInitiatedAt()).isNull();
    }

    // ---- approve: the double-approval guard the audit asked to lock in ----

    @Test
    void cannotReApproveAnAlreadyApprovedRole() {
        SignOff mm = row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED);
        stubChain(mm, row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING), row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));

        // MM is already APPROVED -> it's the Dev Lead's turn, so re-approving MM is rejected, not re-applied.
        assertThatThrownBy(() -> service.approve(SID, SignOffRole.MIGRATION_LEAD, MM_EMAIL, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Dev Lead must act first");
        verify(signOffRepository, never()).save(any());
    }

    @Test
    void cannotApproveOutOfTurn() {
        stubChain(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));
        when(appUserService.roleOf(DEV_EMAIL)).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        assertThatThrownBy(() -> service.approve(SID, SignOffRole.DEV_LEAD, DEV_EMAIL, Boolean.TRUE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Migration Manager must act first");
        verify(signOffRepository, never()).save(any());
    }

    @Test
    void cannotApproveIfNotEligibleForRole() {
        stubChain(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));

        assertThatThrownBy(() -> service.approve(SID, SignOffRole.MIGRATION_LEAD, "stranger@cloudfuze.com", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(signOffRepository, never()).save(any());
    }

    @Test
    void devLeadMustDecideQaRequirementBeforeApproving() {
        stubChain(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED),
                row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));
        when(appUserService.roleOf(DEV_EMAIL)).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        assertThatThrownBy(() -> service.approve(SID, SignOffRole.DEV_LEAD, DEV_EMAIL, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("whether QA Lead approval is required");
        verify(signOffRepository, never()).save(any());
    }

    @Test
    void devLeadApprovingWithQaNotRequiredSkipsQaAndFinalizesDelta() {
        SignOff dev = row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING);
        SignOff qa = row(SignOffRole.QA_LEAD, SignOffStatus.PENDING);
        stubChain(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED), dev, qa);
        when(appUserService.roleOf(DEV_EMAIL)).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        service.approve(SID, SignOffRole.DEV_LEAD, DEV_EMAIL, Boolean.FALSE);

        assertThat(dev.getStatus()).isEqualTo(SignOffStatus.APPROVED);
        assertThat(qa.getStatus()).isEqualTo(SignOffStatus.SKIPPED);
        assertThat(server.getDeltaInitiatedAt()).isNotNull();
        verify(serverService).save(server);
        verify(emailService).notifyMigrationEngineersDeltaInitiated(any(), any(), any(), any());
    }

    @Test
    void qaLeadApprovalFinalizesDelta() {
        SignOff qa = row(SignOffRole.QA_LEAD, SignOffStatus.PENDING);
        stubChain(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED),
                row(SignOffRole.DEV_LEAD, SignOffStatus.APPROVED), qa);
        when(appUserService.roleOf(QA_EMAIL)).thenReturn(Optional.of(AppUserRole.QA_LEAD));

        service.approve(SID, SignOffRole.QA_LEAD, QA_EMAIL, null);

        assertThat(qa.getStatus()).isEqualTo(SignOffStatus.APPROVED);
        assertThat(server.getDeltaInitiatedAt()).isNotNull();
    }

    @Test
    void approveThrowsWhenNoRowForRole() {
        when(signOffRepository.findByServerIdAndRole(SID, SignOffRole.MIGRATION_LEAD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(SID, SignOffRole.MIGRATION_LEAD, MM_EMAIL, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- decline: bounce-back ----

    @Test
    void decliningBouncesPreviousRoleBackToPending() {
        SignOff mm = row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED);
        mm.setApprovedBy(MM_EMAIL);
        SignOff dev = row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING);
        stubChain(mm, dev, row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));
        when(appUserService.roleOf(DEV_EMAIL)).thenReturn(Optional.of(AppUserRole.DEV_LEAD));

        service.decline(SID, SignOffRole.DEV_LEAD, DEV_EMAIL);

        assertThat(dev.getStatus()).isEqualTo(SignOffStatus.DECLINED);
        assertThat(mm.getStatus()).isEqualTo(SignOffStatus.PENDING);
        assertThat(mm.getApprovedBy()).isNull();
    }

    @Test
    void migrationLeadDeclineHasNothingToBounceTo() {
        SignOff mm = row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING);
        stubChain(mm, row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING), row(SignOffRole.QA_LEAD, SignOffStatus.PENDING));

        service.decline(SID, SignOffRole.MIGRATION_LEAD, MM_EMAIL);

        assertThat(mm.getStatus()).isEqualTo(SignOffStatus.DECLINED);
    }

    // ---- createChainIfAbsent ----

    @Test
    void createChainSavesThreeRowsWhenAbsent() {
        when(signOffRepository.findByServerId(SID)).thenReturn(List.of());

        service.createChainIfAbsent(server);

        verify(signOffRepository, times(3)).save(any(SignOff.class));
    }

    @Test
    void createChainIsNoOpWhenAlreadyPresent() {
        when(signOffRepository.findByServerId(SID)).thenReturn(List.of(row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING)));

        service.createChainIfAbsent(server);

        verify(signOffRepository, never()).save(any());
    }

    // ---- removeChainForWithdrawal ----

    @Test
    void withdrawRejectedOnceDeltaInitiated() {
        server.setDeltaInitiatedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.removeChainForWithdrawal(server, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(signOffRepository, never()).deleteAll(any());
    }

    @Test
    void withdrawRejectedOnceChainProgressed() {
        when(signOffRepository.findByServerId(SID)).thenReturn(List.of(
                row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED),
                row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.QA_LEAD, SignOffStatus.PENDING)));

        assertThatThrownBy(() -> service.removeChainForWithdrawal(server, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been approved");
        verify(signOffRepository, never()).deleteAll(any());
    }

    @Test
    void withdrawDeletesCleanPendingChain() {
        when(signOffRepository.findByServerId(SID)).thenReturn(List.of(
                row(SignOffRole.MIGRATION_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.DEV_LEAD, SignOffStatus.PENDING),
                row(SignOffRole.QA_LEAD, SignOffStatus.PENDING)));

        service.removeChainForWithdrawal(server, false);

        verify(signOffRepository).deleteAll(any());
    }

    @Test
    void adminRollbackDeletesEvenApprovedChain() {
        when(signOffRepository.findByServerId(SID)).thenReturn(List.of(
                row(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED),
                row(SignOffRole.DEV_LEAD, SignOffStatus.APPROVED),
                row(SignOffRole.QA_LEAD, SignOffStatus.APPROVED)));

        service.removeChainForWithdrawal(server, true);

        verify(signOffRepository).deleteAll(any());
    }
}
