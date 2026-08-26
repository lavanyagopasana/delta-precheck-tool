package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ProjectAssignmentRequest;
import com.cloudfuze.deltatracker.dto.ProjectDetailDto;
import com.cloudfuze.deltatracker.dto.ProjectUpdateRequest;
import com.cloudfuze.deltatracker.dto.ProjectSummaryDto;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectService {

    // Canonical order lives on SignOffRole.APPROVAL_SEQUENCE (no longer duplicated here).
    private static final List<SignOffRole> APPROVAL_SEQUENCE = SignOffRole.APPROVAL_SEQUENCE;

    private final ProjectRepository projectRepository;
    private final ServerRepository serverRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final SignOffRepository signOffRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final TicketRepository ticketRepository;
    private final ServerService serverService;
    private final WorkspaceCombinationService workspaceCombinationService;
    private final AppUserService appUserService;
    // Owns the per-server cascade delete that delete() used to inline itself.
    private final ServerPurgeService serverPurgeService;

    public ProjectService(ProjectRepository projectRepository,
                           ServerRepository serverRepository,
                           WorkspacePairRepository workspacePairRepository,
                           WorkspaceCombinationRepository workspaceCombinationRepository,
                           SignOffRepository signOffRepository,
                           PreCheckSubmissionRepository preCheckSubmissionRepository,
                           TicketRepository ticketRepository,
                           ServerService serverService,
                           WorkspaceCombinationService workspaceCombinationService,
                           AppUserService appUserService,
                           ServerPurgeService serverPurgeService) {
        this.projectRepository = projectRepository;
        this.serverRepository = serverRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.signOffRepository = signOffRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.ticketRepository = ticketRepository;
        this.serverService = serverService;
        this.workspaceCombinationService = workspaceCombinationService;
        this.appUserService = appUserService;
        this.serverPurgeService = serverPurgeService;
    }

    // email == null means auth isn't configured (or caller identity is unknown) -- in that case
    // every project stays visible, matching how the rest of the app degrades when auth is off.
    public List<ProjectSummaryDto> list(String callerEmail, AppUserRole callerRole) {
        List<Server> allServers = serverRepository.findAll();
        return projectRepository.findAllByOrderByNameAsc().stream()
                .filter(project -> isVisible(project, callerEmail, callerRole))
                .map(project -> buildSummary(project, allServers))
                .toList();
    }

    public ProjectDetailDto getDetail(Long id, String callerEmail, AppUserRole callerRole) {
        Project project = findOrThrow(id);
        if (!isVisible(project, callerEmail, callerRole)) {
            throw new ResourceNotFoundException("Project not found: " + id);
        }
        ProjectSummaryDto summary = buildSummary(project, serverRepository.findAll());
        ProjectDetailDto dto = new ProjectDetailDto();
        copySummary(summary, dto);
        List<ServerReadinessDto> servers = serverService.listReadinessForProject(id);
        servers.forEach(this::applyReadinessStage);
        dto.setServers(servers);
        return dto;
    }

    // Stage is READY only once the server has at least one combination AND every one of them is
    // fully approved (pre-check submitted, all three roles cleared). NOT_SUBMITTED covers a server
    // with no combinations yet, or none of them submitted; IN_PROGRESS names whichever
    // combination/role is next -- so the UI can show something more useful than a plain readiness
    // dot. Each combination now carries its own independent lifecycle (see WorkspaceCombination).
    private void applyReadinessStage(ServerReadinessDto server) {
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getServerId());
        if (combinations.isEmpty()) {
            server.setReadinessStage("NOT_SUBMITTED");
            server.setReadinessDetail("No combinations imported yet");
            return;
        }

        List<Long> combinationIds = combinations.stream().map(WorkspaceCombination::getId).toList();
        Map<Long, PreCheckSubmission> submissionByCombination = preCheckSubmissionRepository
                .findByCombinationIdIn(combinationIds).stream()
                .collect(Collectors.toMap(s -> s.getCombination().getId(), s -> s));
        Map<Long, List<SignOff>> chainByCombination = signOffRepository.findByCombinationIdIn(combinationIds).stream()
                .collect(Collectors.groupingBy(s -> s.getCombination().getId()));

        int readyCount = 0;
        String blockingDetail = null;
        for (WorkspaceCombination combination : combinations) {
            PreCheckSubmission submission = submissionByCombination.get(combination.getId());
            if (submission == null || submission.getStatus() != SubmissionStatus.SUBMITTED) {
                if (blockingDetail == null) {
                    blockingDetail = "\"" + combination.getName() + "\" pre-check isn't submitted yet";
                }
                continue;
            }
            List<SignOff> chain = chainByCombination.getOrDefault(combination.getId(), List.of());
            // SKIPPED only ever appears on a QA Lead row, when the Dev Lead decided QA approval
            // wasn't needed -- it counts the same as approved for readiness purposes.
            SignOffRole nextRole = APPROVAL_SEQUENCE.stream()
                    .filter(role -> chain.stream().noneMatch(s -> s.getRole() == role
                            && (s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED)))
                    .findFirst()
                    .orElse(null);
            if (nextRole == null) {
                readyCount++;
            } else if (blockingDetail == null) {
                blockingDetail = "\"" + combination.getName() + "\": " + roleLabel(nextRole) + " not approved yet";
            }
        }

        if (readyCount == combinations.size()) {
            server.setReadinessStage("READY");
            server.setReadinessDetail(null);
        } else {
            server.setReadinessStage("IN_PROGRESS");
            server.setReadinessDetail(blockingDetail);
        }
    }

    private static String roleLabel(SignOffRole role) {
        return role.label();
    }

    /**
     * True when every role in the approval sequence has cleared for a combination.
     *
     * <p>SKIPPED counts as cleared: it only ever appears on a QA Lead row, set when the Dev Lead
     * decided QA approval wasn't needed, and that finalises the chain exactly as an approval would.
     * Same rule as applyReadinessStage's nextRole lookup above -- kept consistent deliberately, since a
     * chain counted "awaiting approval" here while the same combination reads "Delta Ready" there would
     * be a contradiction on one dashboard.
     */
    private static boolean chainFullyResolved(EnumMap<SignOffRole, SignOffStatus> roles) {
        return APPROVAL_SEQUENCE.stream().allMatch(role -> {
            SignOffStatus status = roles.get(role);
            return status == SignOffStatus.APPROVED || status == SignOffStatus.SKIPPED;
        });
    }

    // A Migration Manager creating a project is automatically that project's manager -- no
    // selection needed. Anyone else (an engineer, an admin) must pick one from the roster.
    // A Migration Engineer creating a project is likewise automatically one of its assigned
    // engineers -- they're clearly going to be working on it, no separate assignment step needed.
    public ProjectSummaryDto create(String name, String createdBy,
                                     String migrationManagerName) {
        String trimmed = name.trim();
        // Project names are globally unique (case-insensitive), not just per product type -- this
        // has to match the DB's unique constraint on name, or a duplicate slips past this check and
        // fails as a raw SQL error instead of a clean one.
        if (projectRepository.existsByNameIgnoreCase(trimmed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A project named \"" + trimmed + "\" already exists.");
        }
        AppUserRole creatorRole = appUserService.roleOf(createdBy).orElse(null);
        boolean creatorIsManager = creatorRole == AppUserRole.MIGRATION_MANAGER;
        boolean creatorIsEngineer = creatorRole == AppUserRole.MIGRATION_ENGINEER;
        String effectiveManager = creatorIsManager ? createdBy : blankToNull(migrationManagerName);
        Set<String> initialEngineers = creatorIsEngineer ? new LinkedHashSet<>(Set.of(createdBy)) : null;

        Project project = new Project(trimmed, createdBy, effectiveManager, initialEngineers);
        Project saved = projectRepository.save(project);
        return buildSummary(saved, serverRepository.findAll());
    }

    public ProjectSummaryDto updateAssignments(Long id, ProjectAssignmentRequest request) {
        Project project = findOrThrow(id);
        project.setEngineerEmails(toSet(request.getEngineerEmails()));
        // Blank is stored as null so "unassigned" is one value everywhere, not a mix of null and "".
        project.setDevLeadEmail(blankToNull(request.getDevLeadEmail()));
        project.setQaLeadEmail(blankToNull(request.getQaLeadEmail()));
        Project saved = projectRepository.save(project);
        return buildSummary(saved, serverRepository.findAll());
    }

    // Edit a project's details (name, product type, Migration Manager). Same permission as delete:
    // admins always; otherwise only an empty project (no servers imported yet) by its creator or
    // Migration Manager. Changing the Migration Manager rolls any in-progress chain back to the
    // manager step (only reachable for admins, since non-admins can only edit an empty project).
    public ProjectSummaryDto updateDetails(Long id, ProjectUpdateRequest request,
                                           String callerEmail, AppUserRole callerRole) {
        Project project = findOrThrow(id);
        List<Server> servers = serverRepository.findAll().stream()
                .filter(s -> s.getProject() != null && s.getProject().getId().equals(id))
                .toList();
        if (!canDelete(project, servers, callerEmail, callerRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to edit this project.");
        }

        String trimmedName = request.getName() == null ? "" : request.getName().trim();
        if (trimmedName.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project name is required.");
        }
        if (!trimmedName.equalsIgnoreCase(project.getName())
                && projectRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A project named \"" + trimmedName + "\" already exists.");
        }

        String newManager = blankToNull(request.getMigrationManagerName());
        boolean mmChanged = newManager == null
                ? project.getMigrationManagerName() != null
                : !newManager.equalsIgnoreCase(project.getMigrationManagerName());
        if (mmChanged) {
            for (Server server : servers) {
                for (WorkspaceCombination combination : workspaceCombinationRepository.findByServerId(server.getId())) {
                    List<SignOff> chain = signOffRepository.findByCombinationId(combination.getId());
                    if (chain.isEmpty()) {
                        continue; // pre-check not submitted on this combination -- no chain to touch
                    }
                    if (newManager == null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "Can't clear the Migration Manager while \"" + server.getName() + " / " + combination.getName()
                                        + "\" has an approval chain in progress -- assign a different manager instead.");
                    }
                    // Changing the manager rolls the approval chain back to the manager step WITHOUT
                    // touching the pre-check itself. Every sign-off returns to PENDING (prior approvals
                    // cleared, QA-required reset), the MM row is re-pointed to the new manager, and a
                    // finalized Delta is un-stamped. The pre-check stays SUBMITTED, so the chain simply
                    // continues fresh from the (new) Migration Manager -- no re-doing the pre-check.
                    for (SignOff signOff : chain) {
                        signOff.setStatus(SignOffStatus.PENDING);
                        signOff.setApprovedBy(null);
                        signOff.setApprovedAt(null);
                        signOff.setQaRequired(null);
                        if (signOff.getRole() == SignOffRole.MIGRATION_LEAD) {
                            signOff.setSignedBy(newManager);
                        }
                        signOffRepository.save(signOff);
                    }
                    if (combination.getDeltaInitiatedAt() != null) {
                        combination.setDeltaInitiatedAt(null);
                        combination.setDeltaInitiatedBy(null);
                    }
                    workspaceCombinationService.save(combination);
                    workspaceCombinationService.recomputeStatus(combination);
                }
            }
            project.setMigrationManagerName(newManager);
        }

        project.setName(trimmedName);
        Project saved = projectRepository.save(project);
        return buildSummary(saved, serverRepository.findAll());
    }

    // Deleting a project cascades to everything hanging off its servers (pre-check items + their
    // evidence files, the submission, the sign-off chain, escalations, and workspace pairs). Two
    // guards apply:
    //   1. Authorization (canDelete): admins can always delete. Everyone else (the creator or the
    //      managing Migration Manager) can delete ONLY while the project is empty -- no servers
    //      imported yet (created by mistake). Once a CSV is uploaded, deletion is admin-only.
    //   2. Audit protection: a project with a Delta-initiated server is a completed migration record
    //      and is never deletable -- not even by an admin (delete it in the DB if truly necessary).
    public void delete(Long id, String callerEmail, AppUserRole callerRole) {
        Project project = findOrThrow(id);
        List<Server> servers = serverRepository.findAll().stream()
                .filter(s -> s.getProject() != null && s.getProject().getId().equals(id))
                .toList();

        if (!canDelete(project, servers, callerEmail, callerRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to delete this project.");
        }

        // The Delta-initiated audit guard protects everyone EXCEPT admins -- admins have full override
        // and can delete a completed-migration project if they really mean to. Checked per
        // combination now, not per server -- each combination has its own independent Delta lifecycle.
        boolean anyDeltaInitiated = servers.stream()
                .flatMap(s -> workspaceCombinationRepository.findByServerId(s.getId()).stream())
                .anyMatch(c -> c.getDeltaInitiatedAt() != null);
        if (anyDeltaInitiated && callerRole != AppUserRole.ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This project has combination(s) with Delta already initiated -- it's a completed migration "
                            + "record and can't be deleted.");
        }

        // One shared definition of "everything hanging off a server", also used by
        // ServerService.decommission -- see ServerPurgeService for why this isn't inlined here anymore
        // (the previous inline version predated Delta cycles and never deleted them, so deleting a
        // project with any Delta cycle failed on a foreign-key constraint).
        servers.forEach(serverPurgeService::purge);
        projectRepository.delete(project);
    }

    // Mirrors the visibility model in isVisible(), plus the creator-until-submit rule. callerEmail
    // == null means auth isn't configured (everything permitted, matching the rest of the app);
    // callerRole == null means an authenticated-but-unrecognized account (allowed nothing).
    private boolean canDelete(Project project, List<Server> servers, String callerEmail, AppUserRole callerRole) {
        if (callerEmail == null) {
            return true;
        }
        if (callerRole == null) {
            return false;
        }
        // Admins can always delete.
        if (callerRole == AppUserRole.ADMIN) {
            return true;
        }
        // Non-admins can delete ONLY a project that has had no action taken yet -- i.e. no servers
        // imported (a project created by mistake). The moment a CSV is uploaded (servers exist),
        // deletion becomes admin-only. For that empty case, the project's creator or its Migration
        // Manager may remove it.
        if (!servers.isEmpty()) {
            return false;
        }
        if (callerEmail.equalsIgnoreCase(project.getCreatedBy())) {
            return true;
        }
        return callerRole == AppUserRole.MIGRATION_MANAGER
                && callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
    }

    private Project findOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + id));
    }

    // Admins and unauthenticated (auth-not-configured) callers see everything. A Migration Manager
    // only sees projects they manage. An engineer only sees projects they created or were added to
    // as a team member. Dev Lead / QA Lead aren't scoped per-project today, so they see everything
    // too -- their involvement is tracked per-server via sign-offs, not project membership.
    //
    // callerEmail == null means auth itself isn't configured (no AZURE_CLIENT_ID) -- there's no
    // identity to scope by, so everything stays visible, matching how the rest of the app degrades
    // when auth is off. That is NOT the same thing as callerRole == null: that means someone
    // authenticated successfully (a real, valid Microsoft account) but has no row in app_users --
    // an unrecognized caller must see nothing, not everything, or the allowlist is meaningless.
    private boolean isVisible(Project project, String callerEmail, AppUserRole callerRole) {
        if (callerEmail == null) {
            return true;
        }
        if (callerRole == null) {
            return false;
        }
        return switch (callerRole) {
            case ADMIN, DEV_LEAD, QA_LEAD -> true;
            case MIGRATION_MANAGER -> callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
            case MIGRATION_ENGINEER -> callerEmail.equalsIgnoreCase(project.getCreatedBy())
                    || project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
        };
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static LinkedHashSet<String> toSet(List<String> emails) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (emails != null) {
            emails.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(set::add);
        }
        return set;
    }

    private static void copySummary(ProjectSummaryDto from, ProjectSummaryDto to) {
        to.setId(from.getId());
        to.setName(from.getName());
        to.setServerCount(from.getServerCount());
        to.setCombinationCount(from.getCombinationCount());
        to.setTotalPairs(from.getTotalPairs());
        to.setReadyServerCount(from.getReadyServerCount());
        to.setNotReadyServerCount(from.getNotReadyServerCount());
        to.setOpenEscalationCount(from.getOpenEscalationCount());
        to.setMigrationManagers(from.getMigrationManagers());
        to.setMigrationManagerName(from.getMigrationManagerName());
        to.setDevLeadEmail(from.getDevLeadEmail());
        to.setQaLeadEmail(from.getQaLeadEmail());
        to.setEngineerEmails(from.getEngineerEmails());
        to.setDevApprovalsDone(from.getDevApprovalsDone());
        to.setDevApprovalsPending(from.getDevApprovalsPending());
        to.setMigrationManagerApprovalsDone(from.getMigrationManagerApprovalsDone());
        to.setMigrationManagerApprovalsPending(from.getMigrationManagerApprovalsPending());
        to.setCombinationsFullyApproved(from.getCombinationsFullyApproved());
        to.setCombinationsAwaitingApproval(from.getCombinationsAwaitingApproval());
        to.setCombinationsDeclined(from.getCombinationsDeclined());
        to.setLastPreCheckSubmittedAt(from.getLastPreCheckSubmittedAt());
        to.setCreatedBy(from.getCreatedBy());
        to.setCreatedAt(from.getCreatedAt());
    }

    private ProjectSummaryDto buildSummary(Project project, List<Server> allServers) {
        List<Server> servers = allServers.stream()
                .filter(s -> s.getProject() != null && s.getProject().getId().equals(project.getId()))
                .toList();

        // Batch-load this project's server-level data in a handful of IN queries instead of ~5
        // queries per server. Same values, same order-independent aggregates -- purely fewer trips.
        List<Long> serverIds = servers.stream().map(Server::getId).toList();
        Map<Long, Long> pairCountByServer = new HashMap<>();
        Map<Long, Long> openEscalationByServer = new HashMap<>();
        List<WorkspaceCombination> combinations = new java.util.ArrayList<>();
        Map<Long, List<WorkspaceCombination>> combinationsByServer = new HashMap<>();
        Map<Long, EnumMap<SignOffRole, SignOffStatus>> signOffByCombination = new HashMap<>();
        Map<Long, PreCheckSubmission> submissionByCombination = new HashMap<>();
        if (!serverIds.isEmpty()) {
            for (WorkspacePair wp : workspacePairRepository.findByServerIdIn(serverIds)) {
                pairCountByServer.merge(wp.getServer().getId(), 1L, Long::sum);
            }
            for (Ticket t : ticketRepository.findAllByCombinationServerIdIn(serverIds)) {
                if (t.getStatus() == TicketStatus.OPEN) {
                    openEscalationByServer.merge(t.getCombination().getServer().getId(), 1L, Long::sum);
                }
            }
            combinations.addAll(workspaceCombinationRepository.findByServerIdIn(serverIds));
            combinationsByServer.putAll(combinations.stream()
                    .collect(Collectors.groupingBy(c -> c.getServer().getId())));
            List<Long> combinationIds = combinations.stream().map(WorkspaceCombination::getId).toList();
            if (!combinationIds.isEmpty()) {
                for (SignOff so : signOffRepository.findByCombinationIdIn(combinationIds)) {
                    signOffByCombination.computeIfAbsent(so.getCombination().getId(), k -> new EnumMap<>(SignOffRole.class))
                            .put(so.getRole(), so.getStatus());
                }
                for (PreCheckSubmission sub : preCheckSubmissionRepository.findByCombinationIdIn(combinationIds)) {
                    submissionByCombination.put(sub.getCombination().getId(), sub);
                }
            }
        }

        long totalPairs = servers.stream().mapToLong(s -> pairCountByServer.getOrDefault(s.getId(), 0L)).sum();
        long readyServers = servers.stream().filter(s -> s.getStatus() == PairStatus.DELTA_READY).count();
        long openEscalations = servers.stream().mapToLong(s -> openEscalationByServer.getOrDefault(s.getId(), 0L)).sum();
        List<String> migrationManagers = project.getMigrationManagerName() != null
                ? List.of(project.getMigrationManagerName())
                : List.of();

        // Counted per combination now, not per server -- a server can have several combinations,
        // each with its own sign-off chain. See DashboardService.getSummary() for why Dev Lead's
        // "pending" also requires Migration Manager to already be approved -- otherwise a
        // not-yet-reached Dev row (created at the same time as Migration Manager's) gets miscounted
        // as an open request.
        long migrationManagerDone = 0;
        long migrationManagerPending = 0;
        long devDone = 0;
        long devPending = 0;
        // Chain-level rollup, counted per combination rather than per role-step. Each combination lands
        // in exactly one bucket, so these three sum to "combinations with a chain at all" and can be
        // reconciled against the Approvals page -- unlike the role-step counters above, which double
        // count a combination across roles and ignore QA_LEAD entirely.
        long fullyApproved = 0;
        long awaitingApproval = 0;
        long declined = 0;
        for (WorkspaceCombination c : combinations) {
            EnumMap<SignOffRole, SignOffStatus> roles = signOffByCombination.get(c.getId());
            SignOffStatus mmStatus = roles == null ? null : roles.get(SignOffRole.MIGRATION_LEAD);
            SignOffStatus devStatus = roles == null ? null : roles.get(SignOffRole.DEV_LEAD);

            if (mmStatus == SignOffStatus.APPROVED) {
                migrationManagerDone++;
            } else if (mmStatus == SignOffStatus.PENDING) {
                migrationManagerPending++;
            }

            if (devStatus == SignOffStatus.APPROVED) {
                devDone++;
            } else if (devStatus == SignOffStatus.PENDING && mmStatus == SignOffStatus.APPROVED) {
                devPending++;
            }

            // No chain yet (pre-check never submitted) is not an approval state -- it belongs to the
            // pre-check funnel, not this one, so such combinations are counted in no bucket at all.
            if (roles == null || roles.isEmpty()) {
                continue;
            }
            if (roles.containsValue(SignOffStatus.DECLINED)) {
                // A decline bounces the chain back a step, so it outranks the other states: the
                // combination is neither done nor quietly waiting, someone has to rework it.
                declined++;
            } else if (chainFullyResolved(roles)) {
                fullyApproved++;
            } else {
                awaitingApproval++;
            }
        }
        LocalDateTime lastPreCheckSubmittedAt = combinations.stream()
                .map(c -> submissionByCombination.get(c.getId()))
                .filter(Objects::nonNull)
                .filter(sub -> sub.getStatus() == SubmissionStatus.SUBMITTED)
                .map(PreCheckSubmission::getSubmittedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        ProjectSummaryDto dto = new ProjectSummaryDto();
        dto.setId(project.getId());
        dto.setName(project.getName());
        dto.setServerCount(servers.size());
        dto.setCombinationCount(combinations.size());
        dto.setTotalPairs(totalPairs);
        dto.setReadyServerCount(readyServers);
        dto.setNotReadyServerCount(servers.size() - readyServers);
        dto.setOpenEscalationCount(openEscalations);
        dto.setMigrationManagers(migrationManagers);
        dto.setMigrationManagerName(project.getMigrationManagerName());
        dto.setDevLeadEmail(project.getDevLeadEmail());
        dto.setQaLeadEmail(project.getQaLeadEmail());
        dto.setEngineerEmails(List.copyOf(project.getEngineerEmails()));
        dto.setDevApprovalsDone(devDone);
        dto.setDevApprovalsPending(devPending);
        dto.setMigrationManagerApprovalsDone(migrationManagerDone);
        dto.setMigrationManagerApprovalsPending(migrationManagerPending);
        dto.setCombinationsFullyApproved(fullyApproved);
        dto.setCombinationsAwaitingApproval(awaitingApproval);
        dto.setCombinationsDeclined(declined);
        dto.setLastPreCheckSubmittedAt(lastPreCheckSubmittedAt);
        dto.setCreatedBy(project.getCreatedBy());
        dto.setCreatedAt(project.getCreatedAt());
        // Ready to decommission once the project has servers, every one of them has at least one
        // combination, and every combination has completed its FINAL Delta.
        //
        // Keyed off finalDeltaCompletedAt, NOT deltaFinishedAt as it was before multi-cycle deltas
        // existed: deltaFinishedAt is now stamped by every intermediate pre-delta and cleared on each
        // rollover, so the old check would have called a whole project decommission-ready as soon as
        // its first pre-delta finished -- long before the migration was actually done.
        dto.setDecommissionReady(!servers.isEmpty() && servers.stream().allMatch(s -> {
            List<WorkspaceCombination> serverCombinations = combinationsByServer.getOrDefault(s.getId(), List.of());
            return !serverCombinations.isEmpty()
                    && serverCombinations.stream().allMatch(WorkspaceCombination::isFinalDeltaComplete);
        }));
        // Per-server decommission counters -- the project-level flag above stays for the Projects list,
        // but decommissioning is actioned per server now, so the detail page needs the breakdown.
        dto.setServersReadyToDecommission(servers.stream()
                .filter(s -> !s.isDecommissioned())
                .filter(s -> {
                    List<WorkspaceCombination> c = combinationsByServer.getOrDefault(s.getId(), List.of());
                    return !c.isEmpty() && c.stream().allMatch(WorkspaceCombination::isFinalDeltaComplete);
                })
                .count());
        dto.setServersDecommissioned(servers.stream().filter(Server::isDecommissioned).count());
        return dto;
    }
}
