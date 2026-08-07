package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.EvidenceRequiredException;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class PreCheckSubmissionService {

    private final PreCheckSubmissionRepository submissionRepository;
    private final PreCheckItemRepository itemRepository;
    private final WorkspaceCombinationService combinationService;
    private final SignOffService signOffService;
    private final AppUserService appUserService;

    public PreCheckSubmissionService(PreCheckSubmissionRepository submissionRepository,
                                      PreCheckItemRepository itemRepository,
                                      WorkspaceCombinationService combinationService,
                                      SignOffService signOffService,
                                      AppUserService appUserService) {
        this.submissionRepository = submissionRepository;
        this.itemRepository = itemRepository;
        this.combinationService = combinationService;
        this.signOffService = signOffService;
        this.appUserService = appUserService;
    }

    public PreCheckSubmissionDto getByCombination(Long combinationId, String viewerEmail) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        // Brings an untouched checklist in line with its product type's item set before rendering it.
        // Needed because items are rows seeded at creation time, so a combination created before Email
        // got its own (shorter) list still holds the Content one. No-ops unless the shape actually
        // differs AND nothing has been filled in -- see realignPreCheckItemsIfUntouched.
        combinationService.realignPreCheckItemsIfUntouched(combination);
        return toDto(getOrCreate(combination), viewerEmail);
    }

    public PreCheckSubmissionDto submit(Long combinationId, SubmissionSubmitRequest request) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        requireNotFinalised(combination);
        PreCheckSubmission submission = getOrCreate(combination);

        if (StringUtils.hasText(submission.getStartedByEmail())
                && !submission.getStartedByEmail().equalsIgnoreCase(request.getSubmittedBy())
                && !appUserService.isAdmin(request.getSubmittedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This pre-check is currently being filled out by " + submission.getStartedByEmail()
                            + " -- only they can submit it.");
        }

        List<PreCheckItem> items = itemRepository.findByCombinationId(combinationId);

        // "Pre Delta Migration" is only required once Delta Type has actually been set to
        // PRE_DELTA -- otherwise the frontend hides it entirely, so it can't have a status,
        // evidence, or a note either. Mirrors PreCheckPanel.js's preDeltaMigrationRequired.
        boolean preDeltaMigrationRequired = items.stream()
                .filter(i -> ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .findFirst()
                .map(i -> i.getStatus() == ItemStatus.PRE_DELTA)
                .orElse(false);

        boolean allCompleted = !items.isEmpty() && items.stream()
                .filter(i -> preDeltaMigrationRequired || !ServerService.isPreDeltaMigrationItem(i.getItemName()))
                .allMatch(PreCheckSubmissionService::isItemComplete);
        boolean allHaveEvidence = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .filter(i -> preDeltaMigrationRequired || !ServerService.isPreDeltaMigrationItem(i.getItemName()))
                .allMatch(i -> StringUtils.hasText(i.getEvidenceFilePath()));
        boolean allHaveNotes = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .filter(i -> preDeltaMigrationRequired || !ServerService.isPreDeltaMigrationItem(i.getItemName()))
                .allMatch(i -> StringUtils.hasText(i.getNotes()));

        String migrationManagerEmail = combination.getServer().getProject() != null
                ? combination.getServer().getProject().getMigrationManagerName() : null;
        if (!StringUtils.hasText(migrationManagerEmail)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This project has no Migration Manager assigned yet -- that has to be set before submitting for review.");
        }
        if (!allCompleted) {
            throw new EvidenceRequiredException("Every item must have a status selected before submitting for Migration Manager review.");
        }
        if (!allHaveEvidence) {
            throw new EvidenceRequiredException("Attach evidence for every checklist item before submitting for Migration Manager review.");
        }
        if (!allHaveNotes) {
            throw new EvidenceRequiredException("Add a note for every checklist item before submitting for Migration Manager review.");
        }

        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedBy(request.getSubmittedBy());
        submission.setSubmittedAt(LocalDateTime.now());

        // Lock in what kind of Delta this cycle is at the moment it goes for review. Copied off the
        // checklist item rather than read from it later because the item resets to NOT_STARTED on the
        // next rollover -- and because pinning it here means an admin editing a submitted form can't
        // retroactively turn a pre-delta into a final one under an approver who already signed off.
        combination.setCurrentDeltaType(resolveDeltaType(items));

        submission = submissionRepository.save(submission);
        combinationService.save(combination);
        combinationService.recomputeStatus(combination);
        signOffService.createChainIfAbsent(combination);
        signOffService.notifyPreCheckSubmitted(combination, request.getSubmittedBy(), migrationManagerEmail);

        return toDto(submission, request.getSubmittedBy());
    }

    // The "Delta Type" item's status doubles as this cycle's declared type (its dropdown offers only
    // Not Started / Pre delta / Final delta). allCompleted above already rejects NOT_STARTED, so
    // reaching the throw means the item is missing entirely -- a combination seeded before the item
    // existed -- which has to block rather than default, since guessing wrong either skips the
    // remaining pre-deltas or never ends the migration.
    private static DeltaType resolveDeltaType(List<PreCheckItem> items) {
        return items.stream()
                .filter(i -> ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .findFirst()
                .map(PreCheckItem::getStatus)
                .map(status -> switch (status) {
                    case PRE_DELTA -> DeltaType.PRE_DELTA;
                    case FINAL_DELTA -> DeltaType.FINAL_DELTA;
                    default -> null;
                })
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Choose whether this is a Pre delta or a Final delta under \"" + ServerService.DELTA_TYPE_ITEM
                                + "\" before submitting for review."));
    }

    // A combination whose Final Delta is done is finished for good -- no more pre-check work on it.
    // Guards submit and (via PreCheckItemService) item edits, so the form can't be quietly refilled
    // after the migration has been signed off as complete.
    private void requireNotFinalised(WorkspaceCombination combination) {
        if (combination.isFinalDeltaComplete()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The Final Delta for this combination is already complete -- its pre-check can't be reopened.");
        }
    }

    /**
     * Un-submits a pre-check so it can be corrected and resubmitted: reverts SUBMITTED -> DRAFT (items
     * keep their data, form unlocks) and removes the approval chain.
     *
     * <p><b>ADMIN only</b>, by explicit product decision -- engineers and Migration Managers no longer
     * withdraw. Since an admin is now the only possible caller, the rollback override is always in
     * effect: a chain that's already been approved, or a Delta that's already been initiated, can
     * still be rolled back here.
     *
     * <p>Consequence worth knowing: a Migration Manager decline leaves the chain with no valid next
     * actor (SignOffService.currentTurn returns null), and the engineer can no longer unblock it
     * themselves -- an admin has to withdraw it. The frontend says so explicitly on a declined form
     * rather than just showing a locked one.
     */
    public PreCheckSubmissionDto withdraw(Long combinationId, String callerEmail) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        PreCheckSubmission submission = submissionRepository.findByCombinationId(combinationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No pre-check submission for this combination."));

        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This pre-check isn't submitted, so there's nothing to withdraw.");
        }
        // callerEmail == null means auth isn't configured at all -- matches how the rest of the app
        // deliberately degrades open in that mode (see SecurityConfig.authConfigured).
        if (callerEmail != null && !appUserService.isAdmin(callerEmail)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only an admin can withdraw a submitted pre-check. Migration Managers review it -- "
                            + "to send it back for rework, decline it instead.");
        }

        signOffService.removeChainForWithdrawal(combination, true);
        // Roll back a finalized Delta too, so the combination returns to its pre-submit state. The
        // cycle's own type is cleared with it: the engineer may well pick a different one on resubmit,
        // and a stale value would otherwise decide the next rollover.
        combination.setDeltaInitiatedAt(null);
        combination.setDeltaInitiatedBy(null);
        combination.setCurrentDeltaType(null);
        combinationService.save(combination);

        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setSubmittedBy(null);
        submission.setSubmittedAt(null);
        // startedByEmail is kept so the original author retains the edit lock and can fix + resubmit.
        submission = submissionRepository.save(submission);
        combinationService.recomputeStatus(combination);

        return toDto(submission, callerEmail);
    }

    private PreCheckSubmission getOrCreate(WorkspaceCombination combination) {
        return submissionRepository.findByCombinationId(combination.getId())
                .orElseGet(() -> submissionRepository.save(new PreCheckSubmission(combination)));
    }

    private PreCheckSubmissionDto toDto(PreCheckSubmission submission, String viewerEmail) {
        WorkspaceCombination combination = submission.getCombination();
        List<PreCheckItem> items = itemRepository.findByCombinationId(combination.getId());

        boolean lockedByOther = submission.getStatus() != SubmissionStatus.SUBMITTED
                && StringUtils.hasText(submission.getStartedByEmail())
                && !submission.getStartedByEmail().equalsIgnoreCase(viewerEmail == null ? "" : viewerEmail.trim());

        PreCheckSubmissionDto dto = new PreCheckSubmissionDto();
        dto.setId(submission.getId());
        dto.setCombinationId(combination.getId());
        dto.setCombinationName(combination.getName());
        dto.setServerId(combination.getServer().getId());
        dto.setServerName(combination.getServer().getName());
        dto.setStatus(submission.getStatus());
        dto.setSubmittedBy(submission.getSubmittedBy());
        dto.setSubmittedAt(submission.getSubmittedAt());
        dto.setStartedByEmail(submission.getStartedByEmail());
        dto.setLockedByOther(lockedByOther);
        dto.setTotalCount(items.size());

        List<String> orderedItemNames = ServerService.preCheckItemsFor(combination.getServer().getProductType());
        List<PreCheckItem> ordered = items.stream()
                .sorted(Comparator.comparingInt(i -> sortIndexOf(orderedItemNames, i.getItemName())))
                .toList();

        if (lockedByOther) {
            dto.setCompletedCount(0);
            dto.setItems(ordered.stream().map(PreCheckItemDto::redacted).toList());
        } else {
            dto.setCompletedCount((int) ordered.stream().filter(PreCheckSubmissionService::isItemComplete).count());
            dto.setItems(ordered.stream().map(PreCheckItemDto::fromEntity).toList());
        }
        return dto;
    }

    /**
     * Position of a stored item within the canonical checklist order.
     *
     * <p>A plain {@code indexOf} is wrong here: it returns -1 for any name not in the canonical list,
     * which sorts that item FIRST -- ahead of Delta Type, which is supposed to lead the form. That is
     * not hypothetical. Item names are persisted per row and are the matching key, so renaming an item
     * ("Pre Delta Migration" -> "Previous Delta Migration", 2026-08-06) leaves every already-seeded
     * checklist holding a name the canonical list no longer contains. Those rows belong in the renamed
     * item's own slot; anything genuinely unrecognised belongs at the end, never at the front.
     * DeltaCycleService.snapshotItems guards the same way for the frozen snapshot.
     */
    private static int sortIndexOf(List<String> orderedItemNames, String itemName) {
        int index = orderedItemNames.indexOf(itemName);
        if (index >= 0) {
            return index;
        }
        if (ServerService.isPreDeltaMigrationItem(itemName)) {
            int canonical = orderedItemNames.indexOf(ServerService.PRE_DELTA_MIGRATION_ITEM);
            if (canonical >= 0) {
                return canonical;
            }
        }
        return Integer.MAX_VALUE;
    }

    // Any real choice counts as done -- Not Started is the only status that blocks submission.
    // (Evidence is still required separately for every item except Delta Type.)
    private static boolean isItemComplete(PreCheckItem item) {
        return item.getStatus() != ItemStatus.NOT_STARTED;
    }
}
