package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemEditDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemUpdateRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckItemEdit;
import com.cloudfuze.deltatracker.entity.PreCheckItemEvidence;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckItemEditRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemEvidenceRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class PreCheckItemService {

    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckItemEvidenceRepository evidenceRepository;
    private final PreCheckItemEditRepository editRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final WorkspaceCombinationService combinationService;
    private final AppUserService appUserService;

    public PreCheckItemService(PreCheckItemRepository preCheckItemRepository,
                                PreCheckItemEvidenceRepository evidenceRepository,
                                PreCheckItemEditRepository editRepository,
                                PreCheckSubmissionRepository preCheckSubmissionRepository,
                                WorkspaceCombinationService combinationService,
                                AppUserService appUserService) {
        this.preCheckItemRepository = preCheckItemRepository;
        this.evidenceRepository = evidenceRepository;
        this.editRepository = editRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.combinationService = combinationService;
        this.appUserService = appUserService;
    }

    public List<PreCheckItemDto> listByCombination(Long combinationId) {
        List<PreCheckItem> items = preCheckItemRepository.findByCombinationId(combinationId);
        // One query for the whole checklist's evidence rather than one per item.
        List<Long> itemIds = items.stream().map(PreCheckItem::getId).toList();
        Map<Long, List<PreCheckItemEvidence>> byItem = itemIds.isEmpty()
                ? Map.of()
                : evidenceRepository.findByItemIdInOrderByUploadedAtAscIdAsc(itemIds).stream()
                        .collect(Collectors.groupingBy(PreCheckItemEvidence::getItemId));
        return items.stream()
                .map(item -> PreCheckItemDto.fromEntity(item, byItem.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    public PreCheckItemDto update(Long combinationId, Long itemId, PreCheckItemUpdateRequest request) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);

        PreCheckItem item = preCheckItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Pre-check item not found: " + itemId));

        if (!item.getCombinationId().equals(combination.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pre-check item does not belong to this combination");
        }
        boolean isAdmin = appUserService.isAdmin(request.getUpdatedBy());
        requireNotFinalised(combination);
        requireUnlocked(combinationId, isAdmin);
        // The ownership lock is bypassed for managers as well as admins: a manager may edit any
        // number of times, including over an engineer who claimed the form. The submitted lock above
        // stays admin-only -- editing after submission changes what an approver already saw.
        claimOrVerifyOwnership(combinationId, request.getUpdatedBy(),
                isAdmin || appUserService.canEditProjectData(request.getUpdatedBy()));

        // Read BEFORE the setters below overwrite them -- this is what the trail compares against.
        ItemStatus previousStatus = item.getStatus();
        String previousNotes = item.getNotes();
        int previousEvidenceCount = evidenceRepository.findByItemIdOrderByUploadedAtAscIdAsc(item.getId()).size();

        item.setStatus(request.getStatus());
        item.setNotes(request.getNotes());
        if (StringUtils.hasText(request.getUpdatedBy())) {
            item.setLastModifiedBy(request.getUpdatedBy().trim());
        }
        item.setLastModifiedAt(LocalDateTime.now());
        item = preCheckItemRepository.save(item);
        List<PreCheckItemEvidence> evidence = applyEvidence(item, request);
        recordEdit(item, request.getUpdatedBy(), previousStatus, previousNotes, previousEvidenceCount,
                evidence.size());

        combinationService.recomputeStatus(combination);

        return PreCheckItemDto.fromEntity(item, evidence);
    }

    /**
     * Appends one row to the item's edit trail, when something actually changed.
     *
     * <p>Called from the single choke point every edit already passes through, so an admin's or a
     * manager's edit is recorded on exactly the same path as an engineer's -- there is no way to
     * edit an item that bypasses this.
     *
     * <p>A save that changed nothing (the panel re-sends the current values on every keystroke-debounce
     * and on status changes) writes no row: a trail padded with no-op entries is one nobody reads.
     */
    private void recordEdit(PreCheckItem item, String editedBy, ItemStatus previousStatus,
                             String previousNotes, int previousEvidenceCount, int newEvidenceCount) {
        PreCheckItemEdit edit = new PreCheckItemEdit(item,
                StringUtils.hasText(editedBy) ? editedBy.trim() : null,
                // Resolved now, not when the trail is read: a role change later must not rewrite
                // history.
                appUserService.roleOf(editedBy).orElse(null));
        edit.setFromStatus(previousStatus);
        edit.setToStatus(item.getStatus());
        edit.setNotesChanged(!java.util.Objects.equals(
                previousNotes == null ? "" : previousNotes,
                item.getNotes() == null ? "" : item.getNotes()));
        int delta = newEvidenceCount - previousEvidenceCount;
        edit.setEvidenceAdded(Math.max(delta, 0));
        edit.setEvidenceRemoved(Math.max(-delta, 0));
        if (edit.isSomethingChanged()) {
            editRepository.save(edit);
        }
    }

    // The name a stored path ends in -- used when a request supplies a path but no display name.
    private static String lastPathSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    /**
     * Every recorded edit across the whole checklist, newest first.
     *
     * <p>One list per form rather than one per item: a toggle on every row was repeated ten times
     * down the page for something read occasionally, and the question being asked ("who has been
     * changing this pre-check") is about the form, not one line of it.
     */
    @Transactional(readOnly = true)
    public List<PreCheckItemEditDto> editHistoryForCombination(Long combinationId) {
        List<Long> itemIds = preCheckItemRepository.findByCombinationId(combinationId).stream()
                .map(PreCheckItem::getId)
                .toList();
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return editRepository.findByItemIdInOrderByEditedAtDescIdDesc(itemIds).stream()
                .map(PreCheckItemEditDto::fromEntity)
                .toList();
    }

    /** Every recorded edit to one item, newest first. Readable by anyone who can see the item. */
    @Transactional(readOnly = true)
    public List<PreCheckItemEditDto> editHistory(Long combinationId, Long itemId) {
        PreCheckItem item = preCheckItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Pre-check item not found: " + itemId));
        if (!item.getCombinationId().equals(combinationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pre-check item does not belong to this combination");
        }
        return editRepository.findByItemIdOrderByEditedAtDescIdDesc(itemId).stream()
                .map(PreCheckItemEditDto::fromEntity)
                .toList();
    }

    /**
     * Writes the item's evidence and returns the resulting list.
     *
     * <p>Two shapes are accepted. A request carrying {@code evidenceFiles} owns the whole list, so
     * the rows are replaced -- that is what lets the panel remove a file without a second endpoint.
     * A request without it (an older client, or any caller that only ever knew one file) keeps the
     * original single-field behaviour, and the table is kept consistent with it either way.
     *
     * <p>In both cases the item's own evidenceFilePath/evidenceFileName end up as the FIRST file.
     * That is what leaves PreCheckSubmissionService's "every item has evidence" precondition, the
     * DeltaCycleItem history snapshot and the existing attachment preview working untouched.
     */
    private List<PreCheckItemEvidence> applyEvidence(PreCheckItem item, PreCheckItemUpdateRequest request) {
        List<PreCheckItemEvidence> rows = new ArrayList<>();
        if (request.getEvidenceFiles() != null) {
            // Deduplicated by the file's ORIGINAL NAME, not its stored path: FileStorageService gives
            // every upload a unique path, so the same screenshot attached four times produced four
            // different paths and four rows that looked identical on screen. The name is the only key
            // that means anything to the person reading the list.
            //
            // Enforced here and not only in the browser, so a double-submit or a retry cannot get
            // past it. The consequence is deliberate: two genuinely different files that share a name
            // cannot both be attached to one item -- rename one.
            Set<String> seenNames = new java.util.LinkedHashSet<>();
            for (PreCheckItemUpdateRequest.EvidenceFileRequest file : request.getEvidenceFiles()) {
                if (!StringUtils.hasText(file.getFilePath())) {
                    continue;
                }
                String displayName = StringUtils.hasText(file.getFileName())
                        ? file.getFileName().trim()
                        : lastPathSegment(file.getFilePath().trim());
                if (!seenNames.add(displayName.toLowerCase())) {
                    continue;
                }
                rows.add(new PreCheckItemEvidence(item, file.getFilePath().trim(),
                        StringUtils.hasText(file.getFileName()) ? file.getFileName().trim() : null,
                        StringUtils.hasText(request.getUpdatedBy()) ? request.getUpdatedBy().trim() : null));
            }
        } else if (StringUtils.hasText(request.getEvidenceFilePath())) {
            rows.add(new PreCheckItemEvidence(item, request.getEvidenceFilePath().trim(),
                    request.getEvidenceFileName(),
                    StringUtils.hasText(request.getUpdatedBy()) ? request.getUpdatedBy().trim() : null));
        }

        // Deleted and re-inserted rather than diffed: the list is at most a handful of rows, and a
        // diff would have to match on path, which is not a stable identity once the same file can be
        // attached twice.
        evidenceRepository.deleteByItemId(item.getId());
        evidenceRepository.flush();
        List<PreCheckItemEvidence> saved = rows.isEmpty() ? List.of() : evidenceRepository.saveAll(rows);

        item.setEvidenceFilePath(saved.isEmpty() ? null : saved.get(0).getFilePath());
        item.setEvidenceFileName(saved.isEmpty() ? null : saved.get(0).getFileName());
        preCheckItemRepository.save(item);
        return saved;
    }

    public void setAllStatus(Long combinationId, ItemStatus status, String updatedBy) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        boolean isAdmin = appUserService.isAdmin(updatedBy);
        requireNotFinalised(combination);
        requireUnlocked(combinationId, isAdmin);
        claimOrVerifyOwnership(combinationId, updatedBy,
                isAdmin || appUserService.canEditProjectData(updatedBy));

        List<PreCheckItem> items = preCheckItemRepository.findByCombinationId(combinationId);
        items.forEach(i -> {
            i.setStatus(status);
            if (StringUtils.hasText(updatedBy)) {
                i.setLastModifiedBy(updatedBy.trim());
            }
            i.setLastModifiedAt(LocalDateTime.now());
        });
        preCheckItemRepository.saveAll(items);

        combinationService.recomputeStatus(combination);
    }

    // Once the Final Delta is done the combination is finished for good, so its checklist is frozen.
    // Unlike the submitted-lock below, admins do NOT bypass this: the point is that the migration is
    // complete and its record shouldn't be quietly rewritten afterwards. There is no in-app way to
    // reopen it -- the decommission-undo that used to serve that purpose was removed when
    // decommissioning became an erase (see ServerService.decommission), so reopening a finalised
    // combination now means editing the database directly.
    private void requireNotFinalised(WorkspaceCombination combination) {
        if (combination.isFinalDeltaComplete()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The Final Delta for this combination is already complete -- its pre-check is closed.");
        }
    }

    // Admins bypass the submitted-lock entirely -- full access to edit even a submitted pre-check.
    private void requireUnlocked(Long combinationId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        preCheckSubmissionRepository.findByCombinationId(combinationId)
                .filter(s -> s.getStatus() == SubmissionStatus.SUBMITTED)
                .ifPresent(s -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "This pre-check has already been submitted for Migration Manager review and is locked.");
                });
    }

    // First person to edit a combination's pre-check claims it -- everyone else is blocked from
    // editing (and from seeing the real content) until that person submits it for review. Admins
    // bypass the claim entirely (full access, and they don't take ownership of the form).
    private void claimOrVerifyOwnership(Long combinationId, String editorEmail, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        PreCheckSubmission submission = preCheckSubmissionRepository.findByCombinationId(combinationId).orElse(null);
        if (submission == null) {
            return;
        }
        if (!StringUtils.hasText(submission.getStartedByEmail())) {
            if (StringUtils.hasText(editorEmail)) {
                submission.setStartedByEmail(editorEmail.trim());
                preCheckSubmissionRepository.save(submission);
            }
            return;
        }
        if (!submission.getStartedByEmail().equalsIgnoreCase(editorEmail == null ? "" : editorEmail.trim())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This pre-check is currently being filled out by " + submission.getStartedByEmail() + ".");
        }
    }
}
