package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class SignOffService {

    // Approvals happen in this fixed order: Migration Manager, then Dev, then QA. Each role can
    // only act once everyone ahead of it in the sequence has approved.
    private static final List<SignOffRole> APPROVAL_SEQUENCE = SignOffRole.APPROVAL_SEQUENCE;

    private final SignOffRepository signOffRepository;
    private final WorkspaceCombinationService combinationService;
    private final EmailService emailService;
    private final AppUserService appUserService;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final TicketService ticketService;

    public SignOffService(SignOffRepository signOffRepository, WorkspaceCombinationService combinationService,
                           EmailService emailService, AppUserService appUserService,
                           PreCheckSubmissionRepository preCheckSubmissionRepository,
                           TicketService ticketService) {
        this.signOffRepository = signOffRepository;
        this.combinationService = combinationService;
        this.emailService = emailService;
        this.appUserService = appUserService;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.ticketService = ticketService;
    }

    // Kicks off the Migration Manager -> Dev Lead -> QA Lead approval chain the moment a
    // combination's pre-check is submitted. A no-op if the chain already exists (e.g. re-triggered
    // on the same combination). The Migration Manager step is a specific person (the project's
    // manager); the Dev/QA steps aren't tied to one named person -- eligibility is checked live
    // against whoever currently holds that role, since there are just two people in each pool and
    // either can act.
    public void createChainIfAbsent(WorkspaceCombination combination) {
        if (!signOffRepository.findByCombinationId(combination.getId()).isEmpty()) {
            return;
        }
        String managerEmail = combination.getServer().getProject() != null
                ? combination.getServer().getProject().getMigrationManagerName() : null;
        signOffRepository.save(new SignOff(combination, SignOffRole.MIGRATION_LEAD,
                managerEmail != null ? managerEmail : "Not assigned"));
        signOffRepository.save(new SignOff(combination, SignOffRole.DEV_LEAD, "Any Dev Lead"));
        signOffRepository.save(new SignOff(combination, SignOffRole.QA_LEAD, "Any QA Lead"));
    }

    // Tears down the approval chain when a pre-check is withdrawn (un-submitted) before anyone has
    // acted on it -- the inverse of createChainIfAbsent. Refuses once the chain has moved forward
    // (any role APPROVED or SKIPPED, or Delta already initiated): at that point a real approval
    // decision exists and the engineer must ask an approver to DECLINE instead of silently erasing
    // it. A stale DECLINED row (bounced back to the engineer) is fine to withdraw over.
    public void removeChainForWithdrawal(WorkspaceCombination combination, boolean allowRollback) {
        // allowRollback == true is the admin override: full rollback even of an approved chain or a
        // finalized Delta. For everyone else the normal guards apply -- you can only withdraw before
        // anyone has approved.
        if (!allowRollback) {
            if (combination.getDeltaInitiatedAt() != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Delta has already been initiated for this combination -- it can't be withdrawn.");
            }
            List<SignOff> signOffs = signOffRepository.findByCombinationId(combination.getId());
            boolean alreadyProgressed = signOffs.stream()
                    .anyMatch(s -> s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED);
            if (alreadyProgressed) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "This pre-check has already been approved by someone in the chain -- ask an approver to "
                                + "decline it instead of withdrawing.");
            }
        }
        signOffRepository.deleteAll(signOffRepository.findByCombinationId(combination.getId()));
    }

    // Approving is only allowed by whoever is eligible for this role, and only once it's actually
    // their turn in the sequence. If the role right after this one had previously been declined,
    // approving here gives it a fresh pending turn again -- and the next role's pool gets an
    // approval-required email. Once the chain is fully done, Delta is automatically marked initiated
    // and Migration Engineers + the project's Migration Manager are notified.
    //
    // qaRequired only matters (and is required) when role == DEV_LEAD: it's the Dev Lead's own
    // decision, made at the moment they approve, on whether this combination also needs QA Lead
    // approval. Saying "no" skips QA Lead entirely and finalizes Delta right away.
    public SignOffApprovalDto approve(Long combinationId, SignOffRole role, String actorEmail, Boolean qaRequired) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        SignOff signOff = signOffRepository.findByCombinationIdAndRole(combinationId, role)
                .orElseThrow(() -> new ResourceNotFoundException("No approval request found for this role."));

        List<SignOff> chain = signOffRepository.findByCombinationId(combinationId);
        requireTurn(role, chain);
        requireEligible(role, actorEmail, combination);

        if (role == SignOffRole.DEV_LEAD && qaRequired == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Specify whether QA Lead approval is required before approving.");
        }

        signOff.setStatus(SignOffStatus.APPROVED);
        signOff.setApprovedBy(actorEmail);
        signOff.setApprovedAt(LocalDateTime.now());
        if (role == SignOffRole.DEV_LEAD) {
            signOff.setQaRequired(qaRequired);
        }
        signOffRepository.save(signOff);

        if (role == SignOffRole.DEV_LEAD && Boolean.FALSE.equals(qaRequired)) {
            SignOff qaSignOff = chain.stream()
                    .filter(s -> s.getRole() == SignOffRole.QA_LEAD)
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("QA Lead sign-off row missing."));
            qaSignOff.setStatus(SignOffStatus.SKIPPED);
            qaSignOff.setApprovedBy("Not required");
            qaSignOff.setApprovedAt(LocalDateTime.now());
            signOffRepository.save(qaSignOff);
            finalizeDelta(combination);
            return toApprovalDto(signOff, combination, signOffRepository.findByCombinationId(combinationId), actorEmail,
                computeCombinationStats(combination));
        }

        int index = APPROVAL_SEQUENCE.indexOf(role);
        if (index == APPROVAL_SEQUENCE.size() - 1) {
            finalizeDelta(combination);
        } else {
            SignOffRole nextRole = APPROVAL_SEQUENCE.get(index + 1);
            // Give the next role a fresh turn if it had previously declined.
            chain.stream()
                    .filter(s -> s.getRole() == nextRole && s.getStatus() == SignOffStatus.DECLINED)
                    .findFirst()
                    .ifPresent(next -> {
                        next.setStatus(SignOffStatus.PENDING);
                        next.setApprovedBy(null);
                        next.setApprovedAt(null);
                        signOffRepository.save(next);
                    });
            notifyNextApprover(combination, nextRole);
        }

        return toApprovalDto(signOff, combination, signOffRepository.findByCombinationId(combinationId), actorEmail,
                computeCombinationStats(combination));
    }

    // Declining bounces the request back one step for rework: the role right before this one (in
    // the Migration Manager -> Dev -> QA sequence) gets reset to pending so they have to reconsider.
    // Declining as Migration Manager (the first step) has nothing earlier to bounce to -- it just
    // sits blocked until the pre-check is resubmitted.
    public SignOffApprovalDto decline(Long combinationId, SignOffRole role, String actorEmail) {
        WorkspaceCombination combination = combinationService.findOrThrow(combinationId);
        SignOff signOff = signOffRepository.findByCombinationIdAndRole(combinationId, role)
                .orElseThrow(() -> new ResourceNotFoundException("No approval request found for this role."));

        List<SignOff> chain = signOffRepository.findByCombinationId(combinationId);
        requireTurn(role, chain);
        requireEligible(role, actorEmail, combination);

        signOff.setStatus(SignOffStatus.DECLINED);
        signOff.setApprovedBy(actorEmail);
        signOff.setApprovedAt(LocalDateTime.now());
        signOffRepository.save(signOff);

        int index = APPROVAL_SEQUENCE.indexOf(role);
        if (index > 0) {
            SignOffRole previousRole = APPROVAL_SEQUENCE.get(index - 1);
            chain.stream()
                    .filter(s -> s.getRole() == previousRole)
                    .findFirst()
                    .ifPresent(previous -> {
                        previous.setStatus(SignOffStatus.PENDING);
                        previous.setApprovedBy(null);
                        previous.setApprovedAt(null);
                        signOffRepository.save(previous);
                    });
        }

        return toApprovalDto(signOff, combination, signOffRepository.findByCombinationId(combinationId), actorEmail,
                computeCombinationStats(combination));
    }

    // Only combinations whose pre-check has actually been submitted show up here -- a combination
    // with no SignOff rows yet (pre-check not submitted) simply isn't part of this list.
    public List<SignOffApprovalDto> listApprovals(String callerEmail, AppUserRole callerRole) {
        List<SignOff> all = signOffRepository.findAll();
        Map<Long, List<SignOff>> byCombination = all.stream()
                .collect(Collectors.groupingBy(s -> s.getCombination().getId()));
        // Pairs / open escalations / submission are combination-level, not row-level -- compute them
        // once per combination instead of once per sign-off row (3x redundant otherwise).
        Map<Long, CombinationStats> statsByCombination = new HashMap<>();

        return all.stream()
                .filter(s -> isVisible(s.getCombination(), callerEmail, callerRole))
                .sorted(Comparator
                        .comparing((SignOff s) -> s.getStatus() == SignOffStatus.PENDING ? 0 : 1)
                        .thenComparing(SignOff::getSignedAt, Comparator.reverseOrder()))
                .map(s -> {
                    WorkspaceCombination combination = s.getCombination();
                    CombinationStats stats = statsByCombination.computeIfAbsent(combination.getId(),
                            k -> computeCombinationStats(combination));
                    return toApprovalDto(s, combination, byCombination.get(combination.getId()), callerEmail, stats);
                })
                .toList();
    }

    // Admins and Dev/QA Leads see every approval request (Dev/QA involvement isn't scoped to one
    // project). A Migration Manager only sees requests for projects they manage. An engineer only
    // sees requests for projects they created or are a team member of.
    private boolean isVisible(WorkspaceCombination combination, String callerEmail, AppUserRole callerRole) {
        if (callerEmail == null || callerRole == null) {
            return true;
        }
        if (callerRole == AppUserRole.ADMIN || callerRole == AppUserRole.DEV_LEAD || callerRole == AppUserRole.QA_LEAD) {
            return true;
        }
        Project project = combination.getServer().getProject();
        if (project == null) {
            return false;
        }
        if (callerRole == AppUserRole.MIGRATION_MANAGER) {
            return callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
        }
        if (callerRole == AppUserRole.MIGRATION_ENGINEER) {
            return callerEmail.equalsIgnoreCase(project.getCreatedBy())
                    || project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
        }
        return false;
    }

    private void requireEligible(SignOffRole role, String actorEmail, WorkspaceCombination combination) {
        if (!isEligible(role, actorEmail, combination)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the " + roleLabel(role) + " can act on this.");
        }
    }

    // Admins can act on any step as an override. Otherwise: Migration Manager must be the exact
    // person the project names; Dev/QA Lead just needs to currently hold that AppUserRole -- either
    // of the two people in that pool can act, since it isn't assigned to one specific name.
    private boolean isEligible(SignOffRole role, String actorEmail, WorkspaceCombination combination) {
        if (actorEmail == null) {
            return false;
        }
        if (appUserService.isAdmin(actorEmail)) {
            return true;
        }
        Server server = combination.getServer();
        return switch (role) {
            case MIGRATION_LEAD -> server.getProject() != null
                    && actorEmail.equalsIgnoreCase(server.getProject().getMigrationManagerName());
            case DEV_LEAD -> appUserService.roleOf(actorEmail).filter(r -> r == AppUserRole.DEV_LEAD).isPresent();
            case QA_LEAD -> appUserService.roleOf(actorEmail).filter(r -> r == AppUserRole.QA_LEAD).isPresent();
        };
    }

    // Rejects the action if this role isn't the one currently allowed to act -- either someone
    // earlier in the sequence hasn't approved yet, or Migration Manager declined and nobody can
    // act until the pre-check is resubmitted.
    private void requireTurn(SignOffRole role, List<SignOff> chain) {
        SignOffRole turn = currentTurn(chain);
        if (turn != role) {
            if (turn == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This can't be acted on right now -- it needs to be resubmitted first.");
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, roleLabel(turn) + " must act first.");
        }
    }

    // Walks the fixed sequence from the start. The first role that hasn't approved is whoever's
    // turn it is. A DECLINED role can only appear at index 0 under our invariants (declining always
    // resets the preceding role to pending), which correctly blocks everyone until resubmission.
    private SignOffRole currentTurn(List<SignOff> chain) {
        for (SignOffRole role : APPROVAL_SEQUENCE) {
            var signOff = chain.stream().filter(s -> s.getRole() == role).findFirst();
            if (signOff.isEmpty() || signOff.get().getStatus() == SignOffStatus.PENDING) {
                return role;
            }
            if (signOff.get().getStatus() == SignOffStatus.DECLINED) {
                return null;
            }
        }
        return null;
    }

    private String overallStatusLabel(List<SignOff> chain) {
        var migrationManager = chain.stream()
                .filter(s -> s.getRole() == SignOffRole.MIGRATION_LEAD)
                .findFirst();
        if (migrationManager.isPresent() && migrationManager.get().getStatus() == SignOffStatus.DECLINED) {
            return "Pending with Migration Engineer";
        }

        boolean allApproved = APPROVAL_SEQUENCE.stream().allMatch(role ->
                chain.stream().anyMatch(s -> s.getRole() == role && isCleared(s)));
        if (allApproved) {
            return "Approved";
        }

        SignOffRole turn = currentTurn(chain);
        return turn == null ? "Approved" : "Pending with " + roleLabel(turn);
    }

    private String currentStatusLabel(List<SignOff> chain) {
        // A stale decline can still be sitting on an earlier role from a prior bounce-back cycle
        // (bounce-back only resets the PRECEDING role, never clears the role that itself declined),
        // so pick whichever decline happened most recently rather than the first one found.
        var declined = chain.stream()
                .filter(s -> s.getStatus() == SignOffStatus.DECLINED)
                .max(Comparator.comparing(SignOff::getApprovedAt));
        if (declined.isPresent()) {
            return "Declined by " + roleLabel(declined.get().getRole());
        }

        SignOffRole lastApproved = null;
        boolean qaSkipped = false;
        for (SignOffRole role : APPROVAL_SEQUENCE) {
            var match = chain.stream().filter(s -> s.getRole() == role).findFirst();
            if (match.isEmpty() || !isCleared(match.get())) {
                break;
            }
            lastApproved = role;
            if (role == SignOffRole.QA_LEAD && match.get().getStatus() == SignOffStatus.SKIPPED) {
                qaSkipped = true;
            }
        }
        if (lastApproved == null) {
            return "Not yet approved by the " + roleLabel(APPROVAL_SEQUENCE.get(0));
        }
        // All roles cleared -- say "Delta Ready" instead of "Approved by QA Lead" to match what the
        // project's server list calls this same fully-approved state.
        if (lastApproved == APPROVAL_SEQUENCE.get(APPROVAL_SEQUENCE.size() - 1)) {
            return qaSkipped ? "Delta Ready — QA Lead not required" : "Delta Ready";
        }
        return "Approved by " + roleLabel(lastApproved);
    }

    // APPROVED and SKIPPED both mean "this role isn't blocking the chain anymore" -- SKIPPED only
    // ever appears on a QA Lead row, when the Dev Lead decided QA approval wasn't needed.
    private boolean isCleared(SignOff signOff) {
        return signOff.getStatus() == SignOffStatus.APPROVED || signOff.getStatus() == SignOffStatus.SKIPPED;
    }

    private String roleLabel(SignOffRole role) {
        return role.label();
    }

    private void finalizeDelta(WorkspaceCombination combination) {
        String requestedBy = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(sub -> sub.getSubmittedBy())
                .filter(s -> s != null && !s.isBlank())
                .orElse("unknown");

        combination.setDeltaInitiatedAt(LocalDateTime.now());
        combination.setDeltaInitiatedBy(requestedBy);
        WorkspaceCombination saved = combinationService.save(combination);

        Server server = saved.getServer();
        String label = server.getName() + " / " + saved.getName();
        emailService.notifyMigrationEngineersDeltaInitiated(label, saved.getDeltaInitiatedBy(),
                saved.getDeltaInitiatedAt(), appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER));

        Project project = server.getProject();
        if (project != null && StringUtils.hasText(project.getMigrationManagerName())) {
            int workspacePairCount = combinationService.pairCount(saved);
            emailService.notifyMigrationManagerDeltaReady(project.getName(), label, workspacePairCount,
                    requestedBy, project.getMigrationManagerName());
        }
    }

    // Emails whoever's pool holds the next role in the sequence that an approval is now waiting on
    // them. Migration Manager is a specific person; Dev/QA Lead are pools of two, either can act.
    private void notifyNextApprover(WorkspaceCombination combination, SignOffRole nextRole) {
        Server server = combination.getServer();
        Project project = server.getProject();
        if (project == null) {
            return;
        }
        List<String> recipients = switch (nextRole) {
            case MIGRATION_LEAD -> StringUtils.hasText(project.getMigrationManagerName())
                    ? List.of(project.getMigrationManagerName()) : List.of();
            case DEV_LEAD -> appUserService.emailsForRole(AppUserRole.DEV_LEAD);
            case QA_LEAD -> appUserService.emailsForRole(AppUserRole.QA_LEAD);
        };
        String label = server.getName() + " / " + combination.getName();
        int workspacePairCount = combinationService.pairCount(combination);
        String submittedBy = preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(sub -> sub.getSubmittedBy())
                .orElse(null);
        emailService.notifyApprovalRequired(roleLabel(nextRole), project.getName(), label,
                workspacePairCount, submittedBy, recipients);
    }

    // Called right after a pre-check is submitted (chain already created) to let the Migration
    // Manager know it's waiting on them, in the same format as every other approval-chain email.
    public void notifyPreCheckSubmitted(WorkspaceCombination combination, String submittedBy, String migrationManagerEmail) {
        Server server = combination.getServer();
        int workspacePairCount = combinationService.pairCount(combination);
        Project project = server.getProject();
        String projectName = project != null ? project.getName() : "-";
        String label = server.getName() + " / " + combination.getName();
        emailService.notifyMigrationManagerPreCheckSubmitted(projectName, label, workspacePairCount,
                submittedBy, migrationManagerEmail);
    }

    // Combination-level stats used to build a SignOffApprovalDto. Computed once per combination
    // (see computeCombinationStats) so its pairs/escalations/submission aren't re-queried for each
    // of its three sign-off rows.
    private record CombinationStats(long openEscalations, int totalPairs, String submittedBy, LocalDateTime submittedAt) {
    }

    private CombinationStats computeCombinationStats(WorkspaceCombination combination) {
        long openEscalations = ticketService.countOpenForCombination(combination.getId());
        int totalPairs = combinationService.pairCount(combination);
        return preCheckSubmissionRepository.findByCombinationId(combination.getId())
                .map(sub -> new CombinationStats(openEscalations, totalPairs, sub.getSubmittedBy(), sub.getSubmittedAt()))
                .orElseGet(() -> new CombinationStats(openEscalations, totalPairs, null, null));
    }

    private void applyCombinationStats(SignOffApprovalDto dto, WorkspaceCombination combination, CombinationStats stats) {
        Server server = combination.getServer();
        dto.setCombinationId(combination.getId());
        dto.setCombinationName(combination.getName());
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setTotalPairs(stats.totalPairs());
        dto.setOpenEscalationCount(stats.openEscalations());
        dto.setReadinessStatus(ServerReadinessDto.computeReadinessStatus(combination.getStatus(), stats.openEscalations()));

        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
        }
    }

    private SignOffApprovalDto toApprovalDto(SignOff signOff, WorkspaceCombination combination, List<SignOff> chain,
                                              String actorEmail, CombinationStats stats) {
        SignOffApprovalDto dto = new SignOffApprovalDto();
        dto.setId(signOff.getId());
        applyCombinationStats(dto, combination, stats);

        dto.setRole(signOff.getRole());
        dto.setAssignedName(signOff.getSignedBy());
        dto.setStatus(signOff.getStatus());
        dto.setSubmittedBy(stats.submittedBy());
        dto.setSubmittedAt(stats.submittedAt());
        dto.setApprovedBy(signOff.getApprovedBy());
        dto.setApprovedAt(signOff.getApprovedAt());
        dto.setOverallStatus(overallStatusLabel(chain));
        dto.setCurrentStatus(currentStatusLabel(chain));

        SignOffRole turn = currentTurn(chain);
        dto.setTurnReady(signOff.getRole() == turn);
        dto.setCanAct(signOff.getStatus() == SignOffStatus.PENDING && signOff.getRole() == turn
                && isEligible(signOff.getRole(), actorEmail, combination));
        return dto;
    }
}
