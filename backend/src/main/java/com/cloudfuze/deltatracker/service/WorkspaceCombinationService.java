package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.CombinationReadinessDto;
import com.cloudfuze.deltatracker.entity.DeltaPhase;
import com.cloudfuze.deltatracker.entity.ItemStatus;
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
import java.util.Comparator;
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
    private final DeltaCycleService deltaCycleService;

    public WorkspaceCombinationService(WorkspaceCombinationRepository combinationRepository,
                                        PreCheckItemRepository preCheckItemRepository,
                                        PreCheckSubmissionRepository preCheckSubmissionRepository,
                                        SignOffRepository signOffRepository,
                                        WorkspacePairRepository workspacePairRepository,
                                        TicketService ticketService,
                                        ServerService serverService,
                                        EmailService emailService,
                                        DeltaCycleService deltaCycleService) {
        this.combinationRepository = combinationRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.signOffRepository = signOffRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.ticketService = ticketService;
        this.serverService = serverService;
        this.emailService = emailService;
        this.deltaCycleService = deltaCycleService;
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
        List<String> items = ServerService.preCheckItemsFor(combination.getServer().getProductType());
        for (String itemName : items) {
            preCheckItemRepository.save(new PreCheckItem(combination, itemName));
        }
        preCheckSubmissionRepository.save(new PreCheckSubmission(combination));
    }

    /**
     * Replaces a combination's checklist when it no longer matches its product type's item set.
     *
     * <p>Why this is needed: checklist items are rows written once at creation, so a combination created
     * before its product type had its own list keeps whatever it was seeded with. Email combinations
     * created while Email reused the Content list (before 2026-08-06) hold all 8 Content items and would
     * keep demanding Permissions Verified / Hyperlinks Verified / Drive changes -- items that don't exist
     * for an email migration and can never be legitimately completed.
     *
     * <p>Two passes, because "replace the list" and "add what is missing" carry very different risks.
     *
     * <ol>
     *   <li><b>Full replace</b> on a checklist nobody has started -- NOT_STARTED submission, no
     *       statuses, no evidence, no notes, no chain. Safe because there is nothing to lose, and it
     *       is the only pass that can REMOVE an item that no longer applies.</li>
     *   <li><b>Additive top-up</b> on a part-filled checklist that has not been submitted. It gains
     *       the items its product type has since acquired and keeps everything already filled in.
     *       Added 2026-09-03: the Folder/File, Email and Channel/DM sections landed while
     *       combinations were mid-fill, and pass 1 alone left every one of those showing the old
     *       list -- the new sections simply never appeared. Nothing is removed here: dropping a
     *       filled item to tidy the shape would destroy an engineer's evidence.</li>
     * </ol>
     *
     * <p>A SUBMITTED pre-check, or one with a sign-off chain, is touched by neither. Adding a blank
     * mandatory row to a form an approver is already reviewing would silently invalidate it.
     */
    public void alignPreCheckItemsToProductType(WorkspaceCombination combination) {
        List<String> expected = ServerService.preCheckItemsFor(combination.getServer().getProductType());
        List<PreCheckItem> existing = preCheckItemRepository.findByCombinationId(combination.getId());

        List<String> existingNames = existing.stream().map(PreCheckItem::getItemName).toList();
        if (existingNames.size() == expected.size() && existingNames.containsAll(expected)) {
            return;
        }

        boolean untouched = existing.stream().allMatch(i -> i.getStatus() == ItemStatus.NOT_STARTED
                && !StringUtils.hasText(i.getEvidenceFilePath())
                && !StringUtils.hasText(i.getNotes()));
        boolean noSubmissionProgress = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(s -> s.getStatus() == SubmissionStatus.NOT_STARTED)
                .orElse(true);
        boolean noChain = signOffRepository.findByCombinationId(combination.getId()).isEmpty();

        // Submitted, or already out for approval: neither pass may touch it.
        if (!noSubmissionProgress || !noChain) {
            return;
        }

        if (untouched) {
            preCheckItemRepository.deleteAll(existing);
            for (String itemName : expected) {
                preCheckItemRepository.save(new PreCheckItem(combination, itemName));
            }
            return;
        }

        // Part-filled: add what is missing, and drop what no longer applies -- but only where nothing
        // has been filled into it.
        //
        // The removal half matters when a SERVER'S PRODUCT TYPE CHANGES. Content -> Email leaves rows
        // like Hyperlinks Verified and Drive changes on the form, and an email migration has no
        // hyperlinks or local drive to evidence: they could never legitimately be completed, so the
        // checklist could never be submitted. Adding alone was not enough.
        //
        // An item somebody HAS filled in is kept even when its product type no longer lists it.
        // Deleting an engineer's status, note and evidence to tidy up the shape of a form is a worse
        // outcome than one stale row -- and a filled row, unlike an empty one, does not block submit.
        for (String itemName : expected) {
            if (!existingNames.contains(itemName)) {
                preCheckItemRepository.save(new PreCheckItem(combination, itemName));
            }
        }
        List<PreCheckItem> staleAndEmpty = existing.stream()
                .filter(item -> !expected.contains(item.getItemName()))
                .filter(item -> item.getStatus() == ItemStatus.NOT_STARTED
                        && !StringUtils.hasText(item.getNotes())
                        && !StringUtils.hasText(item.getEvidenceFilePath()))
                .toList();
        if (!staleAndEmpty.isEmpty()) {
            preCheckItemRepository.deleteAll(staleAndEmpty);
        }
    }

    public void recomputeStatus(WorkspaceCombination combination) {
        SubmissionStatus status = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED);

        boolean anyProgress = status != SubmissionStatus.NOT_STARTED
                || preCheckItemRepository.findByCombinationId(combination.getId()).stream()
                        .anyMatch(item -> item.getStatus() != com.cloudfuze.deltatracker.entity.ItemStatus.NOT_STARTED);

        // DELTA_READY requires the sign-off chain to have actually fully resolved, not just a
        // submitted checklist -- this used to flip green the moment the engineer hit Submit, before
        // any approver had acted, which made the Dashboard's "Delta Ready" figure disagree with its
        // own Approvals donut on the same page for the exact same combination.
        PairStatus newStatus = status == SubmissionStatus.SUBMITTED && isFullyApproved(combination.getId())
                ? PairStatus.DELTA_READY
                : anyProgress ? PairStatus.IN_PROGRESS : PairStatus.PENDING;

        combination.setStatus(newStatus);
        combinationRepository.save(combination);
        serverService.recomputeStatus(combination.getServer());
    }

    // Mirrors applyReadinessStage's own chain-resolved check (SKIPPED counts as cleared -- it only
    // appears on QA Lead when Dev Lead decided QA approval wasn't required).
    private boolean isFullyApproved(Long combinationId) {
        List<SignOff> chain = signOffRepository.findByCombinationId(combinationId);
        return APPROVAL_SEQUENCE.stream().allMatch(role -> chain.stream()
                .anyMatch(s -> s.getRole() == role
                        && (s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED)));
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
        LocalDateTime startedAt = LocalDateTime.now();
        combination.setDeltaStartedAt(startedAt);
        combination.setDeltaStartedBy(actorEmail);
        WorkspaceCombination saved = combinationRepository.save(combination);
        deltaCycleService.markStarted(saved, actorEmail, startedAt);
        notifyManager(saved, true);
        return buildReadiness(saved, true);
    }

    /**
     * Marks the current cycle's migration finished -- and this is where the multi-cycle flow turns
     * over. DeltaCycleService.completeCycle either wipes the checklist and advances to the next
     * pre-delta, or (on a Final Delta) stamps the combination complete for good.
     *
     * <p>The Finish guard reads {@code deltaFinishedAt}, which a rollover clears, so it correctly
     * blocks a double-Finish inside one cycle while still allowing the next cycle its own.
     */
    public CombinationReadinessDto finishDelta(Long combinationId, String actorEmail) {
        WorkspaceCombination combination = findOrThrow(combinationId);
        if (combination.getDeltaStartedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start the Delta migration before marking it finished.");
        }
        if (combination.getDeltaFinishedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta migration is already marked finished for this combination.");
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        combination.setDeltaFinishedAt(finishedAt);
        combination.setDeltaFinishedBy(actorEmail);

        // Notify before the rollover: a pre-delta rollover clears deltaFinishedAt/By off the
        // combination, so the email has to be built while those values are still readable.
        notifyManager(combination, false);
        boolean wasFinalDelta = deltaCycleService.completeCycle(combination, actorEmail, finishedAt);

        WorkspaceCombination saved = combinationRepository.save(combination);
        recomputeStatus(saved);
        if (wasFinalDelta) {
            notifyFinalDeltaComplete(saved);
        }
        return buildReadiness(saved, true);
    }

    // The Final Delta is the milestone that makes a server decommissionable, so it gets its own
    // notification rather than reusing the per-cycle "Delta finished" one -- otherwise the one event
    // that actually ends the engagement looks identical to every intermediate pre-delta.
    private void notifyFinalDeltaComplete(WorkspaceCombination combination) {
        Server server = combination.getServer();
        Project project = server.getProject();
        if (project == null || !StringUtils.hasText(project.getMigrationManagerName())) {
            return;
        }
        boolean serverReady = serverService.isDecommissionReady(server);
        emailService.notifyFinalDeltaComplete(project.getName(), server.getName(), combination.getName(),
                combination.getFinalDeltaCompletedBy(), combination.getFinalDeltaCompletedAt(), serverReady,
                project.getMigrationManagerName());
    }

    private void notifyManager(WorkspaceCombination combination, boolean started) {
        Server server = combination.getServer();
        Project project = server.getProject();
        if (project == null || !StringUtils.hasText(project.getMigrationManagerName())) {
            return;
        }
        int pairCount = pairCount(server.getId(), combination.getName());
        if (started) {
            emailService.notifyMigrationManagerDeltaStarted(project.getName(), server.getName(), combination.getName(),
                    pairCount, combination.getDeltaStartedBy(), combination.getDeltaStartedAt(), project.getMigrationManagerName());
        } else {
            emailService.notifyMigrationManagerDeltaFinished(project.getName(), server.getName(), combination.getName(),
                    pairCount, combination.getDeltaFinishedBy(), combination.getDeltaFinishedAt(), project.getMigrationManagerName());
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
        // Checked before the submission status: a finished Final Delta is terminal, and reporting it as
        // "not submitted" (which it technically is again, post-reset semantics aside) would read as
        // work still outstanding on a combination that's actually done.
        if (dto.isFinalDeltaComplete()) {
            dto.setReadinessStage("COMPLETE");
            dto.setReadinessDetail("Final Delta complete — ready to decommission");
            return;
        }
        if (submissionStatus != SubmissionStatus.SUBMITTED) {
            dto.setReadinessStage("NOT_SUBMITTED");
            dto.setReadinessDetail("Pre-check isn't submitted yet");
            return;
        }
        List<SignOff> chain = signOffRepository.findByCombinationId(combinationId);

        // A decline is a hard stop, not just "not approved yet": declining as Migration Manager leaves
        // the chain with no valid next actor at all (SignOffService.currentTurn returns null), and since
        // withdrawal is admin-only, the engineer can't clear it themselves. Surfaced as its own flag so
        // the pre-check page can say "ask an admin to withdraw this" instead of showing a form that
        // looks merely locked -- otherwise the work silently stalls with nothing explaining why.
        chain.stream()
                .filter(s -> s.getStatus() == SignOffStatus.DECLINED)
                .max(Comparator.comparing(SignOff::getApprovedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(declined -> {
                    dto.setBlockedByDecline(true);
                    dto.setDeclinedByRole(declined.getRole());
                    dto.setDeclinedByRoleLabel(declined.getRole().label());
                    dto.setDeclinedBy(declined.getApprovedBy());
                    dto.setDeclinedAt(declined.getApprovedAt());
                    dto.setDeclineReason(declined.getDeclineReason());
                });

        for (SignOffRole role : APPROVAL_SEQUENCE) {
            boolean cleared = chain.stream()
                    .anyMatch(s -> s.getRole() == role
                            && (s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED));
            if (!cleared) {
                dto.setReadinessStage("IN_PROGRESS");
                dto.setReadinessDetail(dto.isBlockedByDecline()
                        ? "Declined by " + dto.getDeclinedByRoleLabel()
                        : role.label() + " not approved yet");
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
        long totalTickets = ticketService.countTotalForCombination(combination.getId());

        CombinationReadinessDto dto = new CombinationReadinessDto();
        dto.setCombinationId(combination.getId());
        dto.setCombinationName(combination.getName());
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setProductType(server.getProductType());
        dto.setStatus(combination.getStatus());
        dto.setTotalPairs(pairs.size());
        dto.setOpenEscalationCount(openEscalations);
        dto.setTotalTicketCount(totalTickets);
        dto.setReadinessStatus(com.cloudfuze.deltatracker.dto.ServerReadinessDto.computeReadinessStatus(combination.getStatus(), openEscalations));
        dto.setDeltaInitiatedAt(combination.getDeltaInitiatedAt());
        dto.setDeltaInitiatedBy(combination.getDeltaInitiatedBy());
        dto.setDeltaStartedAt(combination.getDeltaStartedAt());
        dto.setDeltaStartedBy(combination.getDeltaStartedBy());
        dto.setDeltaFinishedAt(combination.getDeltaFinishedAt());
        dto.setDeltaFinishedBy(combination.getDeltaFinishedBy());
        dto.setCurrentCycleNumber(combination.getCurrentCycleNumber());
        dto.setCurrentDeltaCycleLabel(combination.currentDeltaCycleLabel());
        dto.setCurrentDeltaMajor(combination.getCurrentDeltaMajor());
        dto.setCurrentDeltaType(combination.getCurrentDeltaType());
        DeltaPhase phase = DeltaPhase.of(combination);
        dto.setDeltaPhase(phase);
        dto.setCurrentDeltaLabel(combination.getCurrentDeltaType() == null
                ? null
                : combination.getCurrentDeltaType().labelWithPhase(combination.currentDeltaCycleLabel(), phase));
        dto.setCompletedCycleCount(deltaCycleService.completedCycleCount(combination.getId()));
        dto.setFinalDeltaCompletedAt(combination.getFinalDeltaCompletedAt());
        dto.setFinalDeltaCompletedBy(combination.getFinalDeltaCompletedBy());
        dto.setFinalDeltaComplete(combination.isFinalDeltaComplete());
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
