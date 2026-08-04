package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.CombinationReadinessDto;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

// Owns the WorkspaceCombination aggregate -- the unit Delta readiness is tracked at (see
// WorkspaceCombination's own javadoc). Mirrors what ServerService used to do for a whole server,
// one level down: seeding the pre-check checklist, computing this combination's own status, and
// the post-Delta Start/Finish lifecycle.
@Service
@Transactional
public class WorkspaceCombinationService {

    private static final List<SignOffRole> APPROVAL_SEQUENCE = SignOffRole.APPROVAL_SEQUENCE;

    private final WorkspaceCombinationRepository combinationRepository;
    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final SignOffRepository signOffRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final TicketService ticketService;
    private final ServerService serverService;
    private final EmailService emailService;

    public WorkspaceCombinationService(WorkspaceCombinationRepository combinationRepository,
                                        PreCheckItemRepository preCheckItemRepository,
                                        PreCheckSubmissionRepository preCheckSubmissionRepository,
                                        SignOffRepository signOffRepository,
                                        WorkspacePairRepository workspacePairRepository,
                                        TicketService ticketService,
                                        ServerService serverService,
                                        EmailService emailService) {
        this.combinationRepository = combinationRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.signOffRepository = signOffRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.ticketService = ticketService;
        this.serverService = serverService;
        this.emailService = emailService;
    }

    public WorkspaceCombination save(WorkspaceCombination combination) {
        return combinationRepository.save(combination);
    }

    public WorkspaceCombination findOrThrow(Long id) {
        return combinationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Combination not found: " + id));
    }

    // Looks up a server's combination by name (case-insensitive), creating and seeding it the first
    // time this name is seen -- called from WorkspacePairService right after a CSV import so a fresh
    // combination always has its checklist ready to fill out.
    public WorkspaceCombination getOrCreate(Server server, String name) {
        String trimmed = name.trim();
        return combinationRepository.findByServerIdAndNameIgnoreCase(server.getId(), trimmed)
                .orElseGet(() -> {
                    WorkspaceCombination combination = combinationRepository.save(new WorkspaceCombination(server, trimmed));
                    seedPreCheckItems(combination);
                    serverService.recomputeStatus(server);
                    return combination;
                });
    }

    private void seedPreCheckItems(WorkspaceCombination combination) {
        for (String itemName : ServerService.PRE_CHECK_ITEMS) {
            preCheckItemRepository.save(new PreCheckItem(combination, itemName));
        }
        preCheckSubmissionRepository.save(new PreCheckSubmission(combination));
    }

    public void recomputeStatus(WorkspaceCombination combination) {
        SubmissionStatus status = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED);

        boolean anyProgress = status != SubmissionStatus.NOT_STARTED
                || preCheckItemRepository.findByCombinationId(combination.getId()).stream()
                        .anyMatch(item -> item.getStatus() != com.cloudfuze.deltatracker.entity.ItemStatus.NOT_STARTED);

        PairStatus newStatus = status == SubmissionStatus.SUBMITTED
                ? PairStatus.DELTA_READY
                : anyProgress ? PairStatus.IN_PROGRESS : PairStatus.PENDING;

        combination.setStatus(newStatus);
        combinationRepository.save(combination);
        serverService.recomputeStatus(combination.getServer());
    }

    public CombinationReadinessDto getReadiness(Long combinationId) {
        return buildReadiness(findOrThrow(combinationId), true);
    }

    // Post-Delta lifecycle (engineer-driven). Start can only happen after Delta is initiated;
    // Finish only after Start. Timestamps are stamped at click time.
    public CombinationReadinessDto startDelta(Long combinationId, String actorEmail) {
        WorkspaceCombination combination = findOrThrow(combinationId);
        if (combination.getDeltaInitiatedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta hasn't been initiated for this combination yet.");
        }
        if (combination.getDeltaStartedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta migration has already been started for this combination.");
        }
        combination.setDeltaStartedAt(LocalDateTime.now());
        combination.setDeltaStartedBy(actorEmail);
        WorkspaceCombination saved = combinationRepository.save(combination);
        notifyManager(saved, true);
        return buildReadiness(saved, true);
    }

    public CombinationReadinessDto finishDelta(Long combinationId, String actorEmail) {
        WorkspaceCombination combination = findOrThrow(combinationId);
        if (combination.getDeltaStartedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start the Delta migration before marking it finished.");
        }
        if (combination.getDeltaFinishedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta migration is already marked finished for this combination.");
        }
        combination.setDeltaFinishedAt(LocalDateTime.now());
        combination.setDeltaFinishedBy(actorEmail);
        WorkspaceCombination saved = combinationRepository.save(combination);
        notifyManager(saved, false);
        return buildReadiness(saved, true);
    }

    private void notifyManager(WorkspaceCombination combination, boolean started) {
        Server server = combination.getServer();
        Project project = server.getProject();
        if (project == null || !StringUtils.hasText(project.getMigrationManagerName())) {
            return;
        }
        String label = server.getName() + " / " + combination.getName();
        int pairCount = pairCount(server.getId(), combination.getName());
        if (started) {
            emailService.notifyMigrationManagerDeltaStarted(project.getName(), label, pairCount,
                    combination.getDeltaStartedBy(), combination.getDeltaStartedAt(), project.getMigrationManagerName());
        } else {
            emailService.notifyMigrationManagerDeltaFinished(project.getName(), label, pairCount,
                    combination.getDeltaFinishedBy(), combination.getDeltaFinishedAt(), project.getMigrationManagerName());
        }
    }

    public int pairCount(WorkspaceCombination combination) {
        return pairCount(combination.getServer().getId(), combination.getName());
    }

    private int pairCount(Long serverId, String combinationName) {
        return (int) workspacePairRepository.findByServerId(serverId).stream()
                .filter(p -> sameCombination(p.getCombination(), combinationName))
                .count();
    }

    private List<WorkspacePair> pairs(Long serverId, String combinationName) {
        return workspacePairRepository.findByServerId(serverId).stream()
                .filter(p -> sameCombination(p.getCombination(), combinationName))
                .toList();
    }

    private boolean sameCombination(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }

    // Mirrors ProjectService.applyReadinessStage, just for one combination instead of aggregating
    // across a server's several -- READY only once the pre-check is submitted AND all three roles
    // have approved (SKIPPED counts as cleared -- it only appears on QA Lead when Dev Lead decided
    // QA approval wasn't required).
    private void applyReadinessStage(CombinationReadinessDto dto, Long combinationId, SubmissionStatus submissionStatus) {
        if (submissionStatus != SubmissionStatus.SUBMITTED) {
            dto.setReadinessStage("NOT_SUBMITTED");
            dto.setReadinessDetail("Pre-check isn't submitted yet");
            return;
        }
        List<SignOff> chain = signOffRepository.findByCombinationId(combinationId);
        for (SignOffRole role : APPROVAL_SEQUENCE) {
            boolean cleared = chain.stream()
                    .anyMatch(s -> s.getRole() == role
                            && (s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED));
            if (!cleared) {
                dto.setReadinessStage("IN_PROGRESS");
                dto.setReadinessDetail(role.label() + " not approved yet");
                return;
            }
        }
        dto.setReadinessStage("READY");
        dto.setReadinessDetail(null);
    }

    private CombinationReadinessDto buildReadiness(WorkspaceCombination combination, boolean includePairs) {
        Server server = combination.getServer();
        List<WorkspacePair> pairs = pairs(server.getId(), combination.getName());
        long openEscalations = ticketService.countOpenForCombination(combination.getId());

        CombinationReadinessDto dto = new CombinationReadinessDto();
        dto.setCombinationId(combination.getId());
        dto.setCombinationName(combination.getName());
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setStatus(combination.getStatus());
        dto.setTotalPairs(pairs.size());
        dto.setOpenEscalationCount(openEscalations);
        dto.setReadinessStatus(com.cloudfuze.deltatracker.dto.ServerReadinessDto.computeReadinessStatus(combination.getStatus(), openEscalations));
        dto.setDeltaInitiatedAt(combination.getDeltaInitiatedAt());
        dto.setDeltaInitiatedBy(combination.getDeltaInitiatedBy());
        dto.setDeltaStartedAt(combination.getDeltaStartedAt());
        dto.setDeltaStartedBy(combination.getDeltaStartedBy());
        dto.setDeltaFinishedAt(combination.getDeltaFinishedAt());
        dto.setDeltaFinishedBy(combination.getDeltaFinishedBy());
        SubmissionStatus submissionStatus = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED);
        dto.setSubmissionStatus(submissionStatus);
        applyReadinessStage(dto, combination.getId(), submissionStatus);
        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
            dto.setMigrationManagerName(server.getProject().getMigrationManagerName());
        }
        if (includePairs) {
            dto.setPairs(pairs.stream().map(com.cloudfuze.deltatracker.dto.WorkspacePairDto::fromEntity).toList());
        }
        return dto;
    }
}
