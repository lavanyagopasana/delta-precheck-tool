package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckItemUpdateRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class PreCheckItemService {

    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final WorkspaceCombinationService combinationService;
    private final AppUserService appUserService;

    public PreCheckItemService(PreCheckItemRepository preCheckItemRepository,
                                PreCheckSubmissionRepository preCheckSubmissionRepository,
                                WorkspaceCombinationService combinationService,
                                AppUserService appUserService) {
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.combinationService = combinationService;
        this.appUserService = appUserService;
    }

    public List<PreCheckItemDto> listByCombination(Long combinationId) {
        return preCheckItemRepository.findByCombinationId(combinationId).stream()
                .map(PreCheckItemDto::fromEntity)
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
        requireUnlocked(combinationId, isAdmin);
        claimOrVerifyOwnership(combinationId, request.getUpdatedBy(), isAdmin);

        item.setStatus(request.getStatus());
        item.setNotes(request.getNotes());
        item.setEvidenceFilePath(request.getEvidenceFilePath());
        item.setEvidenceFileName(request.getEvidenceFileName());
        if (StringUtils.hasText(request.getUpdatedBy())) {
            item.setLastModifiedBy(request.getUpdatedBy().trim());
        }
        item.setLastModifiedAt(LocalDateTime.now());
        item = preCheckItemRepository.save(item);

        combinationService.recomputeStatus(combination);

        return PreCheckItemDto.fromEntity(item);
    }

    public void setAllStatus(Long combinationId, ItemStatus status, String updatedBy) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        boolean isAdmin = appUserService.isAdmin(updatedBy);
        requireUnlocked(combinationId, isAdmin);
        claimOrVerifyOwnership(combinationId, updatedBy, isAdmin);

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
