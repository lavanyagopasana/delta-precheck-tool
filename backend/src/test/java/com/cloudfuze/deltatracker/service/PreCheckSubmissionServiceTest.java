package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
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

import java.time.LocalDateTime;
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
 *
 * <p>Pre-checks are scoped to a WorkspaceCombination now, not a Server directly -- see the
 * per-combination migration in decisions.md. {@code combination.getServer()} still resolves back to
 * the server for the Migration Manager lookup.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreCheckSubmissionServiceTest {

    private static final Long CID = 1L;
    private static final String OWNER = "owner@cloudfuze.com";

    @Mock private PreCheckSubmissionRepository submissionRepository;
    @Mock private PreCheckItemRepository itemRepository;
    @Mock private com.cloudfuze.deltatracker.repository.PreCheckItemEvidenceRepository evidenceRepository;
    @Mock private WorkspaceCombinationService combinationService;
    @Mock private SignOffService signOffService;
    @Mock private AppUserService appUserService;

    private PreCheckSubmissionService service;
    private WorkspaceCombination combination;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new PreCheckSubmissionService(submissionRepository, itemRepository, evidenceRepository,
                combinationService, signOffService, appUserService);
        project = new Project("Alpha", "eng@cloudfuze.com", MM(), null);
        Server server = new Server("SRV-1");
        server.setId(10L);
        server.setProject(project);
        combination = new WorkspaceCombination(server, "Box to OneDrive");
        combination.setId(CID);

        when(combinationService.findOrThrow(CID)).thenReturn(combination);
        when(submissionRepository.save(any(PreCheckSubmission.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appUserService.isAdmin(anyString())).thenReturn(false);
        when(itemRepository.findByCombinationId(anyLong())).thenReturn(List.of());
    }

    private static String MM() {
        return "mgr@cloudfuze.com";
    }

    private PreCheckItem item(String name, ItemStatus status, String evidence, String notes) {
        PreCheckItem i = new PreCheckItem(combination, name);
        i.setStatus(status);
        i.setEvidenceFilePath(evidence);
        i.setNotes(notes);
        return i;
    }

    private PreCheckItem goodItem(String name) {
        return item(name, ItemStatus.COMPLETED, "/uploads/e.png", "looks good");
    }

    // Every submittable checklist now needs a "Delta Type" answer -- it's what settles whether the
    // cycle is a pre-delta (checklist reopens afterwards) or the final one (combination closes), and
    // submit refuses without it. Evidence/notes are deliberately null: this item is exempt from both.
    private PreCheckItem deltaTypeItem(ItemStatus status) {
        return item(ServerService.DELTA_TYPE_ITEM, status, null, null);
    }

    private SubmissionSubmitRequest request(String submittedBy) {
        SubmissionSubmitRequest r = new SubmissionSubmitRequest();
        r.setSubmittedBy(submittedBy);
        return r;
    }

    private PreCheckSubmission submission(SubmissionStatus status, String startedBy, String submittedBy) {
        PreCheckSubmission s = new PreCheckSubmission(combination);
        s.setStatus(status);
        s.setStartedByEmail(startedBy);
        s.setSubmittedBy(submittedBy);
        return s;
    }

    // ---- submit: happy path ----

    @Test
    void submitTransitionsToSubmittedAndCreatesChain() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID))
                .thenReturn(List.of(deltaTypeItem(ItemStatus.PRE_DELTA), goodItem("Item A")));

        PreCheckSubmissionDto dto = service.submit(CID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(dto.getSubmittedBy()).isEqualTo(OWNER);
        // The cycle's type is pinned onto the combination at submit time so a later rollover knows
        // whether to reopen the checklist or close the combination for good.
        assertThat(combination.getCurrentDeltaType()).isEqualTo(DeltaType.PRE_DELTA);
        verify(signOffService).createChainIfAbsent(combination);
        verify(signOffService).notifyPreCheckSubmitted(combination, OWNER, MM());
    }

    @Test
    void submitPinsFinalDeltaTypeWhenChosen() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID))
                .thenReturn(List.of(deltaTypeItem(ItemStatus.FINAL_DELTA), goodItem("Item A")));

        service.submit(CID, request(OWNER));

        assertThat(combination.getCurrentDeltaType()).isEqualTo(DeltaType.FINAL_DELTA);
    }


    /**
     * The first pre-delta has no previous one to report on. combination.currentDeltaMajor defaults
     * to 1, so this is the default state for every fresh combination, not a special setup.
     */
    @Test
    void previousDeltaMigrationItemNotRequiredOnTheFirstPreDelta() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                deltaTypeItem(ItemStatus.PRE_DELTA),
                goodItem("Item A"),
                // Present on the checklist (WorkspaceCombinationService seeds every product-type item
                // regardless of cycle) but deliberately left blank -- submit must not care.
                item(ServerService.PRE_DELTA_MIGRATION_ITEM, ItemStatus.NOT_STARTED, null, null)));

        PreCheckSubmissionDto dto = service.submit(CID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    /**
     * The second pre-delta onwards DOES have one to report on: currentDeltaMajor > 1 turns the same
     * item that was exempt above back into a real requirement.
     */
    @Test
    void previousDeltaMigrationItemIsRequiredFromTheSecondPreDeltaOnwards() {
        combination.setCurrentDeltaMajor(2);
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                deltaTypeItem(ItemStatus.PRE_DELTA),
                goodItem("Item A"),
                item(ServerService.PRE_DELTA_MIGRATION_ITEM, ItemStatus.NOT_STARTED, null, null)));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void previousDeltaMigrationItemSubmitsFineOnceFilledInOnASecondPreDelta() {
        combination.setCurrentDeltaMajor(3);
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                deltaTypeItem(ItemStatus.PRE_DELTA),
                goodItem("Item A"),
                goodItem(ServerService.PRE_DELTA_MIGRATION_ITEM)));

        PreCheckSubmissionDto dto = service.submit(CID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    void submitRejectedWhenDeltaTypeItemIsMissingEntirely() {
        // A combination seeded before the Delta Type item existed. Guessing either way is wrong --
        // pre-delta would never end the migration, final would end it prematurely -- so this blocks.
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(goodItem("Item A")));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Pre delta or a Final delta");
    }

    @Test
    void submitRejectedOnceFinalDeltaIsComplete() {
        combination.setFinalDeltaCompletedAt(LocalDateTime.of(2026, 3, 1, 12, 0));
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.NOT_STARTED, OWNER, null)));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void submitExemptsDeltaTypeItemFromEvidenceAndNotes() {
        PreCheckItem deltaType = item(ServerService.DELTA_TYPE_ITEM, ItemStatus.PRE_DELTA, null, null);
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(deltaType, goodItem("Item A")));

        PreCheckSubmissionDto dto = service.submit(CID, request(OWNER));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    void submitAllowsAdminToBypassEditorLock() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, "someone-else@cloudfuze.com", null)));
        when(itemRepository.findByCombinationId(CID))
                .thenReturn(List.of(deltaTypeItem(ItemStatus.PRE_DELTA), goodItem("Item A")));
        when(appUserService.isAdmin("admin@cloudfuze.com")).thenReturn(true);

        PreCheckSubmissionDto dto = service.submit(CID, request("admin@cloudfuze.com"));

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    // ---- submit: guards ----

    @Test
    void submitRejectedWhenLockedByAnotherEditor() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, "someone-else@cloudfuze.com", null)));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void submitRejectedWhenNoMigrationManager() {
        project.setMigrationManagerName(null);
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(goodItem("Item A")));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no Migration Manager");
        verify(signOffService, never()).createChainIfAbsent(any());
    }

    @Test
    void submitRejectedWhenAnItemHasNoStatus() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.NOT_STARTED, "/uploads/e.png", "note")));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("status selected");
    }

    @Test
    void submitRejectedWhenAnItemHasNoEvidence() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.COMPLETED, null, "note")));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("Attach evidence");
    }

    @Test
    void submitRejectedWhenAnItemHasNoNote() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of(
                goodItem("Item A"),
                item("Item B", ItemStatus.COMPLETED, "/uploads/e.png", "   ")));

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("Add a note");
    }

    @Test
    void submitRejectedWhenNoItemsExist() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
        when(itemRepository.findByCombinationId(CID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(CID, request(OWNER)))
                .isInstanceOf(EvidenceRequiredException.class)
                .hasMessageContaining("status selected");
    }

    // ---- withdraw ----

    @Test
    void withdrawByAdminRevertsToDraftAndRollsBackChain() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));
        when(appUserService.isAdmin("admin@cloudfuze.com")).thenReturn(true);
        combination.setCurrentDeltaType(DeltaType.PRE_DELTA);
        combination.setDeltaInitiatedAt(LocalDateTime.of(2026, 2, 1, 10, 0));

        PreCheckSubmissionDto dto = service.withdraw(CID, "admin@cloudfuze.com");

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(dto.getSubmittedBy()).isNull();
        // allowRollback is unconditionally true now: an admin is the only possible caller, and they're
        // allowed to roll back an approved chain or an already-initiated Delta.
        verify(signOffService).removeChainForWithdrawal(combination, true);
        assertThat(combination.getDeltaInitiatedAt()).isNull();
        // Cleared so a stale type can't decide the next rollover -- the engineer may pick differently.
        assertThat(combination.getCurrentDeltaType()).isNull();
    }

    @Test
    void withdrawRejectedForTheOwnerNowThatItIsAdminOnly() {
        // Explicit product decision: engineers no longer withdraw their own submissions. The known
        // consequence is that a Migration Manager decline needs an admin to unblock it.
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));

        assertThatThrownBy(() -> service.withdraw(CID, OWNER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(signOffService, never()).removeChainForWithdrawal(any(), anyBoolean());
    }

    @Test
    void withdrawRejectedWhenNotSubmitted() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));

        assertThatThrownBy(() -> service.withdraw(CID, OWNER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nothing to withdraw");
        verify(signOffService, never()).removeChainForWithdrawal(any(), anyBoolean());
    }

    @Test
    void withdrawRejectedForNonAdmin() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));

        assertThatThrownBy(() -> service.withdraw(CID, "intruder@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(signOffService, never()).removeChainForWithdrawal(any(), anyBoolean());
    }

    @Test
    void withdrawStillWorksWhenAuthIsNotConfigured() {
        // A null caller email means SecurityConfig is in its fully-open local-dev mode; the whole app
        // degrades open there, and withdraw must not become the one thing that's impossible offline.
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission(SubmissionStatus.SUBMITTED, OWNER, OWNER)));

        PreCheckSubmissionDto dto = service.withdraw(CID, null);

        assertThat(dto.getStatus()).isEqualTo(SubmissionStatus.DRAFT);
    }

    @Test
    void withdrawThrowsWhenNoSubmissionExists() {
        when(submissionRepository.findByCombinationId(CID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(CID, OWNER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- checklist ordering ----

    @Test
    void deltaTypeIsTheFirstItemOnTheForm() {
        // It decides whether Previous Delta Migration applies at all, so it has to be answered first.
        givenStoredItems("Data Verified", "OneTime Migration", ServerService.DELTA_TYPE_ITEM);

        assertThat(itemNamesFromForm()).startsWith(ServerService.DELTA_TYPE_ITEM);
    }

    @Test
    void anItemStoredUnderItsPreRenameNameDoesNotJumpToTheFrontOfTheForm() {
        // The regression this exists for: ordering is `orderedItemNames.indexOf(name)`, and indexOf
        // returns -1 for a name the canonical list no longer contains -- which sorts it FIRST, above
        // Delta Type. Every checklist seeded before "Pre Delta Migration" was renamed to "Previous
        // Delta Migration" still stores the old name, so this hit real data, not a hypothetical.
        givenStoredItems("Pre Delta Migration", ServerService.DELTA_TYPE_ITEM, "OneTime Migration");

        List<String> names = itemNamesFromForm();
        assertThat(names).startsWith(ServerService.DELTA_TYPE_ITEM);
        // It sorts into the renamed item's own slot rather than being dumped at either end.
        assertThat(names.indexOf("Pre Delta Migration")).isEqualTo(2);
    }

    @Test
    void aGenuinelyUnknownItemSortsLastNotFirst() {
        givenStoredItems("Some Removed Item", ServerService.DELTA_TYPE_ITEM, "OneTime Migration");

        assertThat(itemNamesFromForm()).containsExactly(
                ServerService.DELTA_TYPE_ITEM, "OneTime Migration", "Some Removed Item");
    }

    private void givenStoredItems(String... names) {
        List<PreCheckItem> stored = new java.util.ArrayList<>();
        for (String name : names) {
            stored.add(item(name, ItemStatus.NOT_STARTED, null, null));
        }
        when(itemRepository.findByCombinationId(CID)).thenReturn(stored);
        when(submissionRepository.findByCombinationId(CID))
                .thenReturn(Optional.of(submission(SubmissionStatus.DRAFT, OWNER, null)));
    }

    private List<String> itemNamesFromForm() {
        return service.getByCombination(CID, OWNER).getItems().stream()
                .map(PreCheckItemDto::getItemName)
                .toList();
    }
}
