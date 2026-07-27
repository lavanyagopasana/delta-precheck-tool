package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PreCheckItemDto;
import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
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
    private final ServerService serverService;
    private final SignOffService signOffService;

    public PreCheckSubmissionService(PreCheckSubmissionRepository submissionRepository,
                                      PreCheckItemRepository itemRepository,
                                      ServerService serverService,
                                      SignOffService signOffService) {
        this.submissionRepository = submissionRepository;
        this.itemRepository = itemRepository;
        this.serverService = serverService;
        this.signOffService = signOffService;
    }

    public PreCheckSubmissionDto getByServer(Long serverId, String viewerEmail) {
        Server server = serverService.findOrThrow(serverId);
        return toDto(getOrCreate(server), viewerEmail);
    }

    public PreCheckSubmissionDto submit(Long serverId, SubmissionSubmitRequest request) {
        Server server = serverService.findOrThrow(serverId);
        PreCheckSubmission submission = getOrCreate(server);

        if (StringUtils.hasText(submission.getStartedByEmail())
                && !submission.getStartedByEmail().equalsIgnoreCase(request.getSubmittedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This pre-check is currently being filled out by " + submission.getStartedByEmail()
                            + " -- only they can submit it.");
        }

        List<PreCheckItem> items = itemRepository.findByServerId(serverId);

        boolean allCompleted = !items.isEmpty() && items.stream().allMatch(PreCheckSubmissionService::isItemComplete);
        boolean allHaveEvidence = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .allMatch(i -> StringUtils.hasText(i.getEvidenceFilePath()));
        boolean allHaveNotes = items.stream()
                .filter(i -> !ServerService.DELTA_TYPE_ITEM.equals(i.getItemName()))
                .allMatch(i -> StringUtils.hasText(i.getNotes()));

        String migrationManagerEmail = server.getProject() != null ? server.getProject().getMigrationManagerName() : null;
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
        serverService.recomputeStatus(server);
        signOffService.createChainIfAbsent(server);
        signOffService.notifyPreCheckSubmitted(server, request.getSubmittedBy(), migrationManagerEmail);

        return toDto(submission, request.getSubmittedBy());
    }

    private PreCheckSubmission getOrCreate(Server server) {
        return submissionRepository.findByServerId(server.getId())
                .orElseGet(() -> submissionRepository.save(new PreCheckSubmission(server)));
    }

    private PreCheckSubmissionDto toDto(PreCheckSubmission submission, String viewerEmail) {
        List<PreCheckItem> items = itemRepository.findByServerId(submission.getServer().getId());

        boolean lockedByOther = submission.getStatus() != SubmissionStatus.SUBMITTED
                && StringUtils.hasText(submission.getStartedByEmail())
                && !submission.getStartedByEmail().equalsIgnoreCase(viewerEmail == null ? "" : viewerEmail.trim());

        PreCheckSubmissionDto dto = new PreCheckSubmissionDto();
        dto.setId(submission.getId());
        dto.setServerId(submission.getServer().getId());
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
