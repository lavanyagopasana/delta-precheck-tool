package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.DeltaCycleItemRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeltaCycleService} -- the multi-cycle Delta lifecycle. Pure Mockito.
 *
 * <p>The behaviour under test is the branch that makes the whole feature work: finishing a PRE_DELTA
 * wipes the checklist and advances to the next cycle, while finishing a FINAL_DELTA closes the
 * combination permanently. Everything the wipe destroys must first have been captured in the cycle
 * snapshot, so those two halves are tested together rather than in isolation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeltaCycleServiceTest {

    private static final Long CID = 1L;
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 3, 1, 9, 0);
    private static final LocalDateTime FINISHED_AT = LocalDateTime.of(2026, 3, 5, 17, 30);

    @Mock private DeltaCycleRepository cycleRepository;
    @Mock private DeltaCycleItemRepository cycleItemRepository;
    @Mock private DeltaCycleSignOffRepository cycleSignOffRepository;
    @Mock private PreCheckItemRepository preCheckItemRepository;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private SignOffRepository signOffRepository;

    private DeltaCycleService service;
    private WorkspaceCombination combination;
    private PreCheckSubmission submission;

    @BeforeEach
    void setUp() {
        service = new DeltaCycleService(cycleRepository, cycleItemRepository, cycleSignOffRepository,
                preCheckItemRepository, preCheckSubmissionRepository, signOffRepository);

        Server server = new Server("SRV-1");
        server.setId(10L);
        combination = new WorkspaceCombination(server, "Box to OneDrive");
        combination.setId(CID);
        submission = new PreCheckSubmission(combination);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedBy("eng@cloudfuze.com");
        submission.setStartedByEmail("eng@cloudfuze.com");

        when(cycleRepository.save(any(DeltaCycle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cycleRepository.findFirstByCombinationIdOrderByCycleNumberDesc(anyLong())).thenReturn(Optional.empty());
        when(cycleItemRepository.findByCycleIdOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
        when(cycleSignOffRepository.findByCycleId(anyLong())).thenReturn(List.of());
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(List.of());
        when(preCheckSubmissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission));
        when(signOffRepository.findByCombinationId(CID)).thenReturn(List.of());
    }

    private PreCheckItem item(String name, ItemStatus status, String notes, String evidence) {
        PreCheckItem i = new PreCheckItem(combination, name);
        i.setStatus(status);
        i.setNotes(notes);
        i.setEvidenceFilePath(evidence);
        i.setEvidenceFileName("e.png");
        i.setLastModifiedBy("eng@cloudfuze.com");
        i.setLastModifiedAt(SUBMITTED_AT);
        return i;
    }

    private SignOff signOff(SignOffRole role, SignOffStatus status, String approvedBy) {
        SignOff s = new SignOff(combination, role, role.label());
        s.setStatus(status);
        s.setApprovedBy(approvedBy);
        s.setApprovedAt(SUBMITTED_AT);
        return s;
    }

    // Puts a recorded cycle in place as "the current one" so markStarted/completeCycle can find it.
    private DeltaCycle existingCycle(DeltaType type, int number) {
        DeltaCycle cycle = new DeltaCycle(combination, number, type);
        cycle.setId(99L);
        combination.setCurrentCycleNumber(number);
        combination.setCurrentDeltaType(type);
        when(cycleRepository.findFirstByCombinationIdOrderByCycleNumberDesc(CID)).thenReturn(Optional.of(cycle));
        return cycle;
    }

    // ---- recordApproval: the snapshot ----

    @Test
    void recordApprovalFreezesChecklistAndSignOffsForTheCycle() {
        combination.setCurrentDeltaType(DeltaType.PRE_DELTA);
        combination.setDeltaInitiatedAt(SUBMITTED_AT);
        combination.setDeltaInitiatedBy("eng@cloudfuze.com");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(List.of(
                item("Data Verified", ItemStatus.COMPLETED, "checked", "/uploads/a.png")));
        when(signOffRepository.findByCombinationId(CID)).thenReturn(List.of(
                signOff(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED, "mgr@cloudfuze.com"),
                signOff(SignOffRole.QA_LEAD, SignOffStatus.SKIPPED, "Not required")));

        DeltaCycle cycle = service.recordApproval(combination, "eng@cloudfuze.com", SUBMITTED_AT);

        assertThat(cycle.getCycleNumber()).isEqualTo(1);
        assertThat(cycle.getDeltaType()).isEqualTo(DeltaType.PRE_DELTA);
        assertThat(cycle.getStatus()).isEqualTo(DeltaCycleStatus.APPROVED);
        assertThat(cycle.getSubmittedBy()).isEqualTo("eng@cloudfuze.com");

        ArgumentCaptor<List<DeltaCycleItem>> items = ArgumentCaptor.captor();
        verify(cycleItemRepository).saveAll(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(snap -> {
            assertThat(snap.getItemName()).isEqualTo("Data Verified");
            assertThat(snap.getNotes()).isEqualTo("checked");
            // The evidence pointer must survive -- a rollover clears the live one but never the file.
            assertThat(snap.getEvidenceFilePath()).isEqualTo("/uploads/a.png");
        });

        ArgumentCaptor<List<DeltaCycleSignOff>> signOffs = ArgumentCaptor.captor();
        verify(cycleSignOffRepository).saveAll(signOffs.capture());
        assertThat(signOffs.getValue()).extracting(DeltaCycleSignOff::getStatus)
                .containsExactlyInAnyOrder(SignOffStatus.APPROVED, SignOffStatus.SKIPPED);
    }

    @Test
    void recordApprovalReusesTheRowWhenTheSameCycleResolvesAgain() {
        // Admin rolls a chain back and it gets re-approved: must update the existing row rather than
        // insert a second one, which unique(combination_id, cycle_number) would reject.
        DeltaCycle existing = existingCycle(DeltaType.PRE_DELTA, 1);

        DeltaCycle cycle = service.recordApproval(combination, "eng@cloudfuze.com", SUBMITTED_AT);

        assertThat(cycle).isSameAs(existing);
    }

    @Test
    void recordApprovalDefaultsToPreDeltaWhenTypeWasNeverSettled() {
        // Guessing FINAL_DELTA here would irreversibly end the combination on bad data, so the
        // recoverable choice wins.
        combination.setCurrentDeltaType(null);

        assertThat(service.recordApproval(combination, "eng@cloudfuze.com", SUBMITTED_AT).getDeltaType())
                .isEqualTo(DeltaType.PRE_DELTA);
    }

    // ---- completeCycle: the branch ----

    @Test
    void finishingAPreDeltaWipesTheChecklistAndAdvancesTheCycle() {
        existingCycle(DeltaType.PRE_DELTA, 1);
        PreCheckItem filled = item("Data Verified", ItemStatus.COMPLETED, "checked", "/uploads/a.png");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(new ArrayList<>(List.of(filled)));
        List<SignOff> chain = new ArrayList<>(List.of(signOff(SignOffRole.MIGRATION_LEAD, SignOffStatus.APPROVED, "mgr@cloudfuze.com")));
        when(signOffRepository.findByCombinationId(CID)).thenReturn(chain);

        boolean wasFinal = service.completeCycle(combination, "eng@cloudfuze.com", FINISHED_AT);

        assertThat(wasFinal).isFalse();
        assertThat(combination.getCurrentCycleNumber()).isEqualTo(2);
        assertThat(combination.getCurrentDeltaType()).isNull();
        assertThat(combination.isFinalDeltaComplete()).isFalse();
        // Every per-cycle timestamp is cleared, so the next cycle starts from a clean pre-submit state
        // (and the Finish guard in WorkspaceCombinationService lets cycle 2 have its own Finish).
        assertThat(combination.getDeltaInitiatedAt()).isNull();
        assertThat(combination.getDeltaStartedAt()).isNull();
        assertThat(combination.getDeltaFinishedAt()).isNull();

        assertThat(filled.getStatus()).isEqualTo(ItemStatus.NOT_STARTED);
        assertThat(filled.getNotes()).isNull();
        assertThat(filled.getEvidenceFilePath()).isNull();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.NOT_STARTED);
        assertThat(submission.getSubmittedBy()).isNull();
        // Cleared on purpose (unlike a withdrawal): a new cycle is new work and may fall to someone
        // else, so the previous engineer must not keep the edit lock.
        assertThat(submission.getStartedByEmail()).isNull();

        verify(signOffRepository).deleteAll(chain);
    }

    @Test
    void finishingTheFinalDeltaClosesTheCombinationAndResetsNothing() {
        existingCycle(DeltaType.FINAL_DELTA, 3);
        PreCheckItem filled = item("Data Verified", ItemStatus.COMPLETED, "checked", "/uploads/a.png");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(new ArrayList<>(List.of(filled)));

        boolean wasFinal = service.completeCycle(combination, "eng@cloudfuze.com", FINISHED_AT);

        assertThat(wasFinal).isTrue();
        assertThat(combination.isFinalDeltaComplete()).isTrue();
        assertThat(combination.getFinalDeltaCompletedAt()).isEqualTo(FINISHED_AT);
        assertThat(combination.getFinalDeltaCompletedBy()).isEqualTo("eng@cloudfuze.com");
        // Terminal, so no rollover: the cycle number stays put and the record stays readable as-is.
        assertThat(combination.getCurrentCycleNumber()).isEqualTo(3);
        assertThat(filled.getStatus()).isEqualTo(ItemStatus.COMPLETED);
        verify(signOffRepository, never()).deleteAll(any());
    }

    @Test
    void completeCycleStampsTheCycleRecordBeforeBranching() {
        DeltaCycle cycle = existingCycle(DeltaType.FINAL_DELTA, 1);

        service.completeCycle(combination, "eng@cloudfuze.com", FINISHED_AT);

        assertThat(cycle.getStatus()).isEqualTo(DeltaCycleStatus.COMPLETED);
        assertThat(cycle.getDeltaFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(cycle.getDeltaFinishedBy()).isEqualTo("eng@cloudfuze.com");
    }

    @Test
    void completeCycleFallsBackToTheCombinationsTypeWhenNoCycleRowExists() {
        // No cycle row (e.g. data predating this feature) must not silently become a final delta.
        combination.setCurrentDeltaType(null);

        assertThat(service.completeCycle(combination, "eng@cloudfuze.com", FINISHED_AT)).isFalse();
        assertThat(combination.isFinalDeltaComplete()).isFalse();
    }

    // ---- rollover reconciles the checklist against the product type ----

    @Test
    void rolloverDropsStaleItemsAndAddsNewlyRequiredOnes() {
        existingCycle(DeltaType.PRE_DELTA, 1);
        PreCheckItem stale = item("Retired Question", ItemStatus.COMPLETED, "n", "/uploads/a.png");
        PreCheckItem kept = item("Data Verified", ItemStatus.COMPLETED, "n", "/uploads/a.png");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(new ArrayList<>(List.of(stale, kept)));

        service.completeCycle(combination, "eng@cloudfuze.com", FINISHED_AT);

        // "Retired Question" isn't in ServerService's checklist, so it goes.
        ArgumentCaptor<List<PreCheckItem>> deleted = ArgumentCaptor.captor();
        verify(preCheckItemRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(stale);

        // Everything the checklist requires but this combination didn't have is added, so a combination
        // created before an item existed picks it up instead of being submittable without it.
        ArgumentCaptor<List<PreCheckItem>> saved = ArgumentCaptor.captor();
        verify(preCheckItemRepository, org.mockito.Mockito.atLeastOnce()).saveAll(saved.capture());
        List<String> addedNames = saved.getAllValues().stream()
                .flatMap(List::stream)
                .map(PreCheckItem::getItemName)
                .toList();
        assertThat(addedNames).contains(ServerService.DELTA_TYPE_ITEM);
    }

    // ---- markStarted ----

    @Test
    void markStartedMovesTheCycleToRunning() {
        DeltaCycle cycle = existingCycle(DeltaType.PRE_DELTA, 1);

        service.markStarted(combination, "eng@cloudfuze.com", SUBMITTED_AT);

        assertThat(cycle.getStatus()).isEqualTo(DeltaCycleStatus.RUNNING);
        assertThat(cycle.getDeltaStartedBy()).isEqualTo("eng@cloudfuze.com");
    }

    @Test
    void markStartedIsANoOpWhenTheCycleRecordIsMissing() {
        // Tolerated rather than fatal: the combination's own timestamps drive the live UI, so a
        // bookkeeping gap must not block a real migration from starting.
        service.markStarted(combination, "eng@cloudfuze.com", SUBMITTED_AT);

        verify(cycleRepository, never()).save(any());
    }

    // ---- history ----

    @Test
    void historyIsEmptyWithoutQueryingChildrenWhenNoCyclesExist() {
        when(cycleRepository.findByCombinationIdOrderByCycleNumberAsc(CID)).thenReturn(List.of());

        assertThat(service.history(CID)).isEmpty();
        verify(cycleItemRepository, never()).findByCycleIdInOrderBySortOrderAsc(any());
    }

    @Test
    void historyLabelsEachCycleAndOrdersItsSignOffsByTheApprovalSequence() {
        DeltaCycle first = new DeltaCycle(combination, 1, DeltaType.PRE_DELTA);
        first.setId(101L);
        DeltaCycle second = new DeltaCycle(combination, 2, DeltaType.FINAL_DELTA);
        second.setId(102L);
        when(cycleRepository.findByCombinationIdOrderByCycleNumberAsc(CID)).thenReturn(List.of(first, second));
        // Deliberately out of order coming back from the repository.
        when(cycleSignOffRepository.findByCycleIdIn(any())).thenReturn(List.of(
                cycleSignOff(first, SignOffRole.QA_LEAD),
                cycleSignOff(first, SignOffRole.MIGRATION_LEAD),
                cycleSignOff(first, SignOffRole.DEV_LEAD)));
        when(cycleItemRepository.findByCycleIdInOrderBySortOrderAsc(any())).thenReturn(List.of());

        var history = service.history(CID);

        assertThat(history).extracting("label").containsExactly("Pre-Delta 1", "Final Delta");
        assertThat(history.get(0).getSignOffs()).extracting("role")
                .containsExactly(SignOffRole.MIGRATION_LEAD, SignOffRole.DEV_LEAD, SignOffRole.QA_LEAD);
    }

    private DeltaCycleSignOff cycleSignOff(DeltaCycle cycle, SignOffRole role) {
        DeltaCycleSignOff snap = new DeltaCycleSignOff(cycle, signOff(role, SignOffStatus.APPROVED, "x@cloudfuze.com"));
        snap.setCycleId(cycle.getId());
        return snap;
    }
}
