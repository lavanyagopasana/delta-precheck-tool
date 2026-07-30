package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.EvidenceRequiredException;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PreCheckSubmissionService}. Pure Mockito. Covers submit's three preconditions
 * (every item has a status, evidence, and a note), the Migration-Manager-required guard, the
 * editor-lock ("locked by another editor"), the Delta Type exemption from evidence/notes, and the
 * withdraw state-transition guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreCheckSubmissionServiceTest {

    private static final Long SID = 1L;
    private static final String OWNER = "owner@cloudfuze.com";

    @Mock private PreCheckSubmissionRepository submissionRepository;
    @Mock private PreCheckItemRepository itemRepository;
    @Mock private ServerService serverService;
    @Mock private SignOffService signOffService;
    @Mock private AppUserService appUserService;

    private PreCheckSubmissionService service;
    private Server server;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new PreCheckSubmissionService(submissionRepository, itemRepository, serverService,
                signOffService, appUserService);
        project = new Project("Alpha", ProductType.MESSAGE, "eng@cloudfuze.com", MM(), null);
        server = new Server("SRV-1");
        server.setId(SID);
        server.setProject(project);

        when(serverService.findOrThrow(SID)).thenReturn(server);
        when(submissionRepository.save(any(PreCheckSubmission.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appUserService.isAdmin(anyString())).thenReturn(false);
        when(itemRepository.findByServerId(anyLong())).thenReturn(List.of());
    }

    private static String MM() {
        return "mgr@cloudfuze.com";
    }

    private PreCheckItem item(String name, ItemStatus status, String evidence, String notes) {
        PreCheckItem i = new PreCheckItem(server, name);
        i.setStatus(status);
        i.setEvidenceFilePath(evidence);
        i.setNotes(notes);
        return i;
    }

    private PreCheckItem goodItem(String name) {
        return item(name, ItemStatus.COMPLETED, "/uploads/e.png", "looks good");
    }

    private SubmissionSubmitRequest request(String submittedBy) {
        SubmissionSubmitRequest r = new SubmissionSubmitRequest();
        r.setSubmittedBy(submittedBy);
        return r;
    }

    private PreCheckSubmission submission(SubmissionStatus status, String startedBy, String submittedBy) {
        PreCheckSubmission s = new PreCheckSubmission(server);
        s.setStatus(status);
        s.setStartedByEmail(startedBy);
        s.setSubmittedBy(submittedBy);
        return s;
    }

    // ---- submit: happy path ----

    @Test
    void submitTransitionsToSubmittedAndCreatesChain() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(goodItem("Item A")));

        PreCheckSubmissionDto dto = service.submit(SID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(dto.getSubmittedBy()).isEqualTo(OWNER);
        verify(signOffService).createChainIfAbsent(server);
        verify(signOffService).notifyPreCheckSubmitted(server, OWNER, MM());
    }

    @Test
    void submitExemptsDeltaTypeItemFromEvidenceAndNotes() {
        PreCheckItem deltaType = item(ServerService.DELTA_TYPE_ITEM, ItemStatus.PRE_DELTA, null, null);
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(deltaType, goodItem("Item A")));

        PreCheckSubmissionDto dto = service.submit(SID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    void submitAllowsAdminToBypassEditorLock() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, "someone-else@cloudfuze.com", null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(goodItem("Item A")));
        when(appUserService.isAdmin("admin@cloudfuze.com")).thenReturn(true);

        PreCheckSubmissionDto dto = service.submit(SID, request("admin@cloudfuze.com"));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    // ---- submit: guards ----

    @Test
    void submitRejectedWhenLockedByAnotherEditor() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, "someone-else@cloudfuze.com", null)));

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void submitRejectedWhenNoMigrationManager() {
        project.setMigrationManagerName(null);
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(goodItem("Item A")));

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no Migration Manager");
        verify(signOffService, never()).createChainIfAbsent(any());
    }

    @Test
    void submitRejectedWhenAnItemHasNoStatus() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.NOT_STARTED, "/uploads/e.png", "note")));

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("status selected");
    }

    @Test
    void submitRejectedWhenAnItemHasNoEvidence() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.COMPLETED, null, "note")));

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("Attach evidence");
    }

    @Test
    void submitRejectedWhenAnItemHasNoNote() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.COMPLETED, "/uploads/e.png", "   ")));

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("Add a note");
    }

    @Test
    void submitRejectedWhenNoItemsExist() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByServerId(SID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(SID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("status selected");
    }

    // ---- withdraw ----

    @Test
    void withdrawByOwnerRevertsToDraftAndRemovesChain() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));

        PreCheckSubmissionDto dto = service.withdraw(SID, OWNER);

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(dto.getSubmittedBy()).isNull();
        verify(signOffService).removeChainForWithdrawal(server, false);
    }

    @Test
    void withdrawRejectedWhenNotSubmitted() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));

        assertThatThrownBy(() -> service.withdraw(SID, OWNER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nothing to withdraw");
        verify(signOffService, never()).removeChainForWithdrawal(any(), anyBoolean());
    }

    @Test
    void withdrawRejectedForNonOwnerNonAdmin() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));

        assertThatThrownBy(() -> service.withdraw(SID, "intruder@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(signOffService, never()).removeChainForWithdrawal(any(), anyBoolean());
    }

    @Test
    void withdrawThrowsWhenNoSubmissionExists() {
        when(submissionRepository.findByServerId(SID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(SID, OWNER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
