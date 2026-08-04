package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
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
        return toDto(getOrCreate(combination), viewerEmail);
    }

    public PreCheckSubmissionDto submit(Long combinationId, SubmissionSubmitRequest request) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
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
                .filter(i -> preDeltaMigrationRequired || !ServerService.PRE_DELTA_MIGRATION_ITEM.equals(i.getItemName()))
                .allMatch(PreCheckSubmissionService::isItemComplete);
        boolean allHaveEvidence = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .filter(i -> preDeltaMigrationRequired || !ServerService.PRE_DELTA_MIGRATION_ITEM.equals(i.getItemName()))
                .allMatch(i -> StringUtils.hasText(i.getEvidenceFilePath()));
        boolean allHaveNotes = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .filter(i -> preDeltaMigrationRequired || !ServerService.PRE_DELTA_MIGRATION_ITEM.equals(i.getItemName()))
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

        submission = submissionRepository.save(submission);
        combinationService.recomputeStatus(combination);
        signOffService.createChainIfAbsent(combination);
        signOffService.notifyPreCheckSubmitted(combination, request.getSubmittedBy(), migrationManagerEmail);

        return toDto(submission, request.getSubmittedBy());
    }

    // Un-submits a pre-check that was submitted by mistake so it can be corrected and resubmitted:
    // reverts SUBMITTED -> DRAFT (items keep their data, form unlocks) and removes the pending
    // approval chain. Only works while nobody has approved yet -- SignOffService.removeChainForWithdrawal
    // enforces that and throws a 409 otherwise. Allowed for the person who submitted/started it or the
    // project's Migration Manager (ADMIN is intentionally out here, mirroring how ADMIN is excluded
    // from filling out/submitting pre-checks in SecurityConfig).
    public PreCheckSubmissionDto withdraw(Long combinationId, String callerEmail) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        PreCheckSubmission submission = submissionRepository.findByCombinationId(combinationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No pre-check submission for this combination."));

        if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This pre-check isn't submitted, so there's nothing to withdraw.");
        }
        // Admins have full access: they can withdraw anyone's submission AND roll back a chain that's
        // already been approved or even had Delta initiated (removeChainForWithdrawal's allowRollback).
        boolean isAdmin = appUserService.isAdmin(callerEmail);
        if (!isAdmin && !canWithdraw(submission, callerEmail)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only the person who submitted this pre-check can withdraw it (or an admin). "
                            + "Migration Managers review it -- to send it back, decline it instead.");
        }

        signOffService.removeChainForWithdrawal(combination, isAdmin);
        if (isAdmin && combination.getDeltaInitiatedAt() != null) {
            // Admin rollback of a finalized Delta -- un-stamp it so the combination returns to pre-submit.
            combination.setDeltaInitiatedAt(null);
            combination.setDeltaInitiatedBy(null);
            combinationService.save(combination);
        }

        submission.setStatus(SubmissionStatus.DRAFT);
        submission.setSubmittedBy(null);
        submission.setSubmittedAt(null);
        // startedByEmail is kept so the same person retains the edit lock and can fix + resubmit.
        submission = submissionRepository.save(submission);
        combinationService.recomputeStatus(combination);

        return toDto(submission, callerEmail);
    }

    // Withdraw is the engineer's "undo my submission" -- only the person who submitted it or started
    // (owns) the pre-check. Migration Managers do NOT withdraw (in review they approve/decline);
    // admins withdraw/roll back via the isAdmin bypass in withdraw(), not through this check.
    private boolean canWithdraw(PreCheckSubmission submission, String callerEmail) {
        if (callerEmail == null) {
            return true; // auth not configured -- matches how the rest of the app degrades open
        }
        return callerEmail.equalsIgnoreCase(submission.getSubmittedBy())
                || callerEmail.equalsIgnoreCase(submission.getStartedByEmail());
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

        List<PreCheckItem> ordered = items.stream()
                .sorted(Comparator.comparing(i -> ServerService.PRE_CHECK_ITEMS.indexOf(i.getItemName())))
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

    // Any real choice counts as done -- Not Started is the only status that blocks submission.
    // (Evidence is still required separately for every item except Delta Type.)
    private static boolean isItemComplete(PreCheckItem item) {
        return item.getStatus() != ItemStatus.NOT_STARTED;
    }
}
