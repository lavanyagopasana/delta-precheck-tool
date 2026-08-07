package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DeltaCycleDto;
import com.cloudfuze.deltatracker.dto.DeltaCycleItemDto;
import com.cloudfuze.deltatracker.dto.DeltaCycleSignOffDto;
import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.DeltaCycleItemRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Owns the multi-cycle Delta lifecycle: a combination runs any number of PRE_DELTA cycles, each one
 * resetting the checklist for the next, before a single FINAL_DELTA cycle ends it for good.
 *
 * <p>Deliberately depends on repositories only, never on the services that call it
 * (SignOffService, WorkspaceCombinationService, PreCheckSubmissionService all point *at* this one),
 * so there's no circular bean dependency to work around.
 *
 * <p>This is the only class that writes to delta_cycles / delta_cycle_items / delta_cycle_signoffs,
 * and the only class that resets live pre-check state. Confining both to one place is what keeps the
 * ~dozen existing {@code findByCombinationId} call sites elsewhere correct without modification --
 * they all mean "the current cycle", and that stays true because the live tables only ever hold one.
 */
@Service
@Transactional
public class DeltaCycleService {

    private final DeltaCycleRepository cycleRepository;
    private final DeltaCycleItemRepository cycleItemRepository;
    private final DeltaCycleSignOffRepository cycleSignOffRepository;
    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final SignOffRepository signOffRepository;

    public DeltaCycleService(DeltaCycleRepository cycleRepository,
                             DeltaCycleItemRepository cycleItemRepository,
                             DeltaCycleSignOffRepository cycleSignOffRepository,
                             PreCheckItemRepository preCheckItemRepository,
                             PreCheckSubmissionRepository preCheckSubmissionRepository,
                             SignOffRepository signOffRepository) {
        this.cycleRepository = cycleRepository;
        this.cycleItemRepository = cycleItemRepository;
        this.cycleSignOffRepository = cycleSignOffRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.signOffRepository = signOffRepository;
    }

    /**
     * Writes the cycle record the moment the sign-off chain fully resolves (called from
     * SignOffService.finalizeDelta), freezing the checklist and all three approval outcomes as they
     * stood at approval time. This is the single snapshot write point.
     *
     * <p>Nothing is recorded for a submission that's withdrawn before approval -- that's why there's
     * no IN_REVIEW DeltaCycleStatus: an unapproved cycle simply has no row, and the live
     * PreCheckSubmission/SignOff rows represent that phase.
     *
     * <p>Idempotent per cycle number: re-resolving the same cycle (e.g. an admin rolling a chain back
     * and re-approving it) updates the existing row instead of violating
     * unique(combination_id, cycle_number).
     */
    public DeltaCycle recordApproval(WorkspaceCombination combination, String submittedBy, LocalDateTime submittedAt) {
        // A cycle whose type was never settled at submit time can't be recorded meaningfully -- treat
        // a missing type as PRE_DELTA (the non-terminal, recoverable choice) rather than guessing
        // FINAL_DELTA and irreversibly ending the combination on bad data.
        DeltaType deltaType = combination.getCurrentDeltaType() != null
                ? combination.getCurrentDeltaType()
                : DeltaType.PRE_DELTA;

        DeltaCycle cycle = cycleRepository
                .findFirstByCombinationIdOrderByCycleNumberDesc(combination.getId())
                .filter(existing -> existing.getCycleNumber() == combination.getCurrentCycleNumber())
                .orElseGet(() -> new DeltaCycle(combination, combination.getCurrentCycleNumber(), deltaType));

        cycle.setDeltaType(deltaType);
        cycle.setStatus(DeltaCycleStatus.APPROVED);
        cycle.setSubmittedBy(submittedBy);
        cycle.setSubmittedAt(submittedAt);
        cycle.setDeltaInitiatedAt(combination.getDeltaInitiatedAt());
        cycle.setDeltaInitiatedBy(combination.getDeltaInitiatedBy());
        cycle = cycleRepository.save(cycle);

        snapshotItems(cycle, combination);
        snapshotSignOffs(cycle, combination);
        return cycle;
    }

    // Replaces (rather than appends to) any existing snapshot so the idempotent re-approval path above
    // can't accumulate duplicate rows.
    private void snapshotItems(DeltaCycle cycle, WorkspaceCombination combination) {
        cycleItemRepository.deleteAll(cycleItemRepository.findByCycleIdOrderBySortOrderAsc(cycle.getId()));

        List<String> order = ServerService.preCheckItemsFor(combination.getServer().getProductType());
        List<PreCheckItem> live = new ArrayList<>(preCheckItemRepository.findByCombinationId(combination.getId()));
        // Items not in the canonical list sort last (indexOf returns -1) rather than first, so an
        // unexpected extra item can't displace the real checklist order.
        live.sort(Comparator.comparingInt(item -> {
            int index = order.indexOf(item.getItemName());
            return index < 0 ? Integer.MAX_VALUE : index;
        }));

        List<DeltaCycleItem> snapshot = new ArrayList<>(live.size());
        for (int i = 0; i < live.size(); i++) {
            snapshot.add(new DeltaCycleItem(cycle, live.get(i), i));
        }
        cycleItemRepository.saveAll(snapshot);
    }

    private void snapshotSignOffs(DeltaCycle cycle, WorkspaceCombination combination) {
        cycleSignOffRepository.deleteAll(cycleSignOffRepository.findByCycleId(cycle.getId()));

        List<SignOff> live = signOffRepository.findByCombinationId(combination.getId());
        cycleSignOffRepository.saveAll(live.stream()
                .map(signOff -> new DeltaCycleSignOff(cycle, signOff))
                .toList());
    }

    // Mirrors the Start click onto the cycle record. A missing cycle row is tolerated rather than
    // fatal: the combination's own timestamps remain the source of truth for the live UI, so failing
    // here would block a real migration over a bookkeeping gap.
    public void markStarted(WorkspaceCombination combination, String actorEmail, LocalDateTime startedAt) {
        currentCycle(combination).ifPresent(cycle -> {
            cycle.setStatus(DeltaCycleStatus.RUNNING);
            cycle.setDeltaStartedAt(startedAt);
            cycle.setDeltaStartedBy(actorEmail);
            cycleRepository.save(cycle);
        });
    }

    /**
     * Closes out the current cycle after the engineer marks the migration finished, then branches on
     * the cycle's declared type -- the heart of the multi-pre-delta flow:
     *
     * <ul>
     *   <li><b>PRE_DELTA</b> -- roll over: the checklist is wiped back to empty, the sign-off chain is
     *       deleted, the combination's delta timestamps are cleared, and currentCycleNumber advances.
     *       The engineer can immediately start filling out the next pre-delta.</li>
     *   <li><b>FINAL_DELTA</b> -- terminal: finalDeltaCompletedAt/By is stamped and nothing is reset.
     *       The combination is locked from further pre-check work and now counts toward its server
     *       becoming decommission-ready.</li>
     * </ul>
     *
     * @return true if this was the FINAL_DELTA (so callers can fire the "ready to decommission"
     *         notification), false if it rolled over into another pre-delta.
     */
    public boolean completeCycle(WorkspaceCombination combination, String actorEmail, LocalDateTime finishedAt) {
        DeltaType deltaType = currentCycle(combination)
                .map(cycle -> {
                    cycle.setStatus(DeltaCycleStatus.COMPLETED);
                    cycle.setDeltaFinishedAt(finishedAt);
                    cycle.setDeltaFinishedBy(actorEmail);
                    cycleRepository.save(cycle);
                    return cycle.getDeltaType();
                })
                // Same tolerance as markStarted: fall back to what the combination itself declared, and
                // to PRE_DELTA (recoverable) over FINAL_DELTA (irreversible) if even that is missing.
                .orElseGet(() -> combination.getCurrentDeltaType() != null
                        ? combination.getCurrentDeltaType()
                        : DeltaType.PRE_DELTA);

        if (deltaType == DeltaType.FINAL_DELTA) {
            combination.setFinalDeltaCompletedAt(finishedAt);
            combination.setFinalDeltaCompletedBy(actorEmail);
            return true;
        }
        rollOver(combination);
        return false;
    }

    /**
     * Resets the combination for its next pre-delta. Everything cleared here is recoverable from the
     * snapshot written by {@link #recordApproval}, which is why it's safe to wipe.
     *
     * <p>Note {@code startedByEmail} is cleared too -- unlike a withdrawal, which deliberately keeps
     * the edit lock so the same person fixes their own mistake. A new cycle is genuinely new work and
     * may well fall to a different engineer; keeping the old lock would silently freeze everyone else
     * out of a fresh checklist.
     *
     * <p>Evidence files under uploads/ are never touched -- only the live pointer to them is cleared,
     * so each snapshot's evidence stays viewable indefinitely.
     */
    private void rollOver(WorkspaceCombination combination) {
        resetChecklist(combination);

        preCheckSubmissionRepository.findByCombinationId(combination.getId()).ifPresent(submission -> {
            submission.setStatus(SubmissionStatus.NOT_STARTED);
            submission.setSubmittedBy(null);
            submission.setSubmittedAt(null);
            submission.setStartedByEmail(null);
            preCheckSubmissionRepository.save(submission);
        });

        // The live chain has to go rather than be reset in place: unique(combination_id, role) allows
        // only one chain per combination, and SignOffService.createChainIfAbsent keys off "no rows
        // exist" to build the next one. The outcomes survive in delta_cycle_signoffs.
        signOffRepository.deleteAll(signOffRepository.findByCombinationId(combination.getId()));

        combination.setDeltaInitiatedAt(null);
        combination.setDeltaInitiatedBy(null);
        combination.setDeltaStartedAt(null);
        combination.setDeltaStartedBy(null);
        combination.setDeltaFinishedAt(null);
        combination.setDeltaFinishedBy(null);
        combination.setCurrentDeltaType(null);
        combination.setCurrentCycleNumber(combination.getCurrentCycleNumber() + 1);
    }

    /**
     * Wipes every live checklist item back to unfilled, and reconciles the item set against the
     * checklist its server's product type currently calls for.
     *
     * <p>The reconcile matters because items are otherwise only ever seeded once, at combination
     * creation: without it, a combination whose product type was corrected between cycles -- or one
     * created before a new item was added to {@code ServerService.PRE_CHECK_ITEMS} -- would keep
     * showing a stale checklist forever, and could be submitted while silently skipping a
     * now-required item.
     */
    private void resetChecklist(WorkspaceCombination combination) {
        List<String> expected = ServerService.preCheckItemsFor(combination.getServer().getProductType());
        List<PreCheckItem> live = preCheckItemRepository.findByCombinationId(combination.getId());

        List<PreCheckItem> stale = live.stream()
                .filter(item -> !expected.contains(item.getItemName()))
                .toList();
        if (!stale.isEmpty()) {
            preCheckItemRepository.deleteAll(stale);
        }

        List<PreCheckItem> keep = live.stream()
                .filter(item -> expected.contains(item.getItemName()))
                .toList();
        keep.forEach(item -> {
            item.setStatus(ItemStatus.NOT_STARTED);
            item.setNotes(null);
            item.setEvidenceFilePath(null);
            item.setEvidenceFileName(null);
            item.setLastModifiedBy(null);
            item.setLastModifiedAt(null);
        });
        preCheckItemRepository.saveAll(keep);

        List<String> present = keep.stream().map(PreCheckItem::getItemName).toList();
        preCheckItemRepository.saveAll(expected.stream()
                .filter(name -> !present.contains(name))
                .map(name -> new PreCheckItem(combination, name))
                .toList());
    }

    private java.util.Optional<DeltaCycle> currentCycle(WorkspaceCombination combination) {
        return cycleRepository.findFirstByCombinationIdOrderByCycleNumberDesc(combination.getId())
                .filter(cycle -> cycle.getCycleNumber() == combination.getCurrentCycleNumber());
    }

    /**
     * A combination's full Delta history, oldest cycle first. Batches the item and sign-off snapshot
     * loads into one query each for the whole history rather than one pair per cycle, matching how
     * SignOffService.listApprovals prefetches instead of querying per row.
     */
    @Transactional(readOnly = true)
    public List<DeltaCycleDto> history(Long combinationId) {
        List<DeltaCycle> cycles = cycleRepository.findByCombinationIdOrderByCycleNumberAsc(combinationId);
        if (cycles.isEmpty()) {
            return List.of();
        }
        List<Long> cycleIds = cycles.stream().map(DeltaCycle::getId).toList();

        Map<Long, List<DeltaCycleItemDto>> itemsByCycle = cycleItemRepository
                .findByCycleIdInOrderBySortOrderAsc(cycleIds).stream()
                .collect(Collectors.groupingBy(DeltaCycleItem::getCycleId,
                        Collectors.mapping(DeltaCycleItemDto::fromEntity, Collectors.toList())));

        Map<Long, List<DeltaCycleSignOffDto>> signOffsByCycle = cycleSignOffRepository
                .findByCycleIdIn(cycleIds).stream()
                .collect(Collectors.groupingBy(DeltaCycleSignOff::getCycleId,
                        Collectors.mapping(DeltaCycleSignOffDto::fromEntity, Collectors.toList())));

        return cycles.stream()
                .map(cycle -> DeltaCycleDto.fromEntity(cycle,
                        orderedSignOffs(signOffsByCycle.getOrDefault(cycle.getId(), List.of())),
                        itemsByCycle.getOrDefault(cycle.getId(), List.of())))
                .toList();
    }

    // Always Migration Manager -> Dev Lead -> QA Lead, so the snapshot reads as the chain it was.
    private List<DeltaCycleSignOffDto> orderedSignOffs(List<DeltaCycleSignOffDto> signOffs) {
        return signOffs.stream()
                .sorted(Comparator.comparingInt(s -> com.cloudfuze.deltatracker.entity.SignOffRole
                        .APPROVAL_SEQUENCE.indexOf(s.getRole())))
                .toList();
    }

    // How many cycles this combination has completed -- i.e. currentCycleNumber - 1 expressed as real
    // recorded rows, used for the "Pre-Deltas Done" figures in the server/project rollups.
    @Transactional(readOnly = true)
    public long completedCycleCount(Long combinationId) {
        return cycleRepository.countByCombinationId(combinationId);
    }
}
