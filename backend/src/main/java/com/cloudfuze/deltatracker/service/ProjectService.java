package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ProjectAssignmentRequest;
import com.cloudfuze.deltatracker.dto.ProjectDetailDto;
import com.cloudfuze.deltatracker.dto.ProjectUpdateRequest;
import com.cloudfuze.deltatracker.dto.ProjectSummaryDto;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.EscalationRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class ProjectService {

    // Same order as SignOffService.APPROVAL_SEQUENCE: Migration Manager, then Dev, then QA.
    private static final List<SignOffRole> APPROVAL_SEQUENCE =
            List.of(SignOffRole.MIGRATION_LEAD, SignOffRole.DEV_LEAD, SignOffRole.QA_LEAD);

    private final ProjectRepository projectRepository;
    private final ServerRepository serverRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final EscalationService escalationService;
    private final SignOffRepository signOffRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final PreCheckItemRepository preCheckItemRepository;
    private final EscalationRepository escalationRepository;
    private final FileStorageService fileStorageService;
    private final ServerService serverService;
    private final AppUserService appUserService;

    public ProjectService(ProjectRepository projectRepository,
                           ServerRepository serverRepository,
                           WorkspacePairRepository workspacePairRepository,
                           EscalationService escalationService,
                           SignOffRepository signOffRepository,
                           PreCheckSubmissionRepository preCheckSubmissionRepository,
                           PreCheckItemRepository preCheckItemRepository,
                           EscalationRepository escalationRepository,
                           FileStorageService fileStorageService,
                           ServerService serverService,
                           AppUserService appUserService) {
        this.projectRepository = projectRepository;
        this.serverRepository = serverRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.escalationService = escalationService;
        this.signOffRepository = signOffRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.escalationRepository = escalationRepository;
        this.fileStorageService = fileStorageService;
        this.serverService = serverService;
        this.appUserService = appUserService;
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

    // Stage is READY only once the pre-check is submitted AND all three roles have approved.
    // NOT_SUBMITTED covers the pre-check step itself; IN_PROGRESS covers the approval chain, naming
    // whichever role is next -- so the UI can show something more useful than a plain readiness dot.
    private void applyReadinessStage(ServerReadinessDto server) {
        Long serverId = server.getServerId();
        SubmissionStatus submissionStatus = preCheckSubmissionRepository.findByServerId(serverId)
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED);
        if (submissionStatus != SubmissionStatus.SUBMITTED) {
            server.setReadinessStage("NOT_SUBMITTED");
            server.setReadinessDetail("Pre-check isn't submitted yet");
            return;
        }
        for (SignOffRole role : APPROVAL_SEQUENCE) {
            // SKIPPED only ever appears on a QA Lead row, when the Dev Lead decided QA approval
            // wasn't needed for this server -- it counts the same as approved for readiness purposes.
            boolean approved = signOffRepository.findByServerIdAndRole(serverId, role)
                    .filter(s -> s.getStatus() == SignOffStatus.APPROVED || s.getStatus() == SignOffStatus.SKIPPED)
                    .isPresent();
            if (!approved) {
                server.setReadinessStage("IN_PROGRESS");
                server.setReadinessDetail(roleLabel(role) + " not approved yet");
                return;
            }
        }
        server.setReadinessStage("READY");
        server.setReadinessDetail(null);
    }

    private static String roleLabel(SignOffRole role) {
        return switch (role) {
            case MIGRATION_LEAD -> "Migration Manager";
            case DEV_LEAD -> "Dev Lead";
            case QA_LEAD -> "QA Lead";
        };
    }

    // A Migration Manager creating a project is automatically that project's manager -- no
    // selection needed. Anyone else (an engineer, an admin) must pick one from the roster.
    // A Migration Engineer creating a project is likewise automatically one of its assigned
    // engineers -- they're clearly going to be working on it, no separate assignment step needed.
    public ProjectSummaryDto create(String name, ProductType productType, String createdBy,
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

        Project project = new Project(trimmed, productType, createdBy, effectiveManager, initialEngineers);
        Project saved = projectRepository.save(project);
        return buildSummary(saved, serverRepository.findAll());
    }

    public ProjectSummaryDto updateAssignments(Long id, ProjectAssignmentRequest request) {
        Project project = findOrThrow(id);
        project.setEngineerEmails(toSet(request.getEngineerEmails()));
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
                List<SignOff> chain = signOffRepository.findByServerId(server.getId());
                if (chain.isEmpty()) {
                    continue; // pre-check not submitted on this server -- no chain to touch
                }
                if (newManager == null) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Can't clear the Migration Manager while server \"" + server.getName()
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
                if (server.getDeltaInitiatedAt() != null) {
                    server.setDeltaInitiatedAt(null);
                    server.setDeltaInitiatedBy(null);
                }
                serverService.save(server);
                serverService.recomputeStatus(server);
            }
            project.setMigrationManagerName(newManager);
        }

        project.setName(trimmedName);
        project.setProductType(request.getProductType());
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
        // and can delete a completed-migration project if they really mean to.
        boolean anyDeltaInitiated = servers.stream().anyMatch(s -> s.getDeltaInitiatedAt() != null);
        if (anyDeltaInitiated && callerRole != AppUserRole.ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This project has server(s) with Delta already initiated -- it's a completed migration "
                            + "record and can't be deleted.");
        }

        for (Server server : servers) {
            List<PreCheckItem> items = preCheckItemRepository.findByServerId(server.getId());
            items.forEach(item -> fileStorageService.delete(item.getEvidenceFilePath()));
            preCheckItemRepository.deleteAll(items);
            preCheckSubmissionRepository.findByServerId(server.getId())
                    .ifPresent(preCheckSubmissionRepository::delete);
            signOffRepository.deleteAll(signOffRepository.findByServerId(server.getId()));
            escalationRepository.deleteAll(escalationRepository.findByServerId(server.getId()));
            // Workspace pairs cascade via Server's @OneToMany(orphanRemoval=true) on delete.
            serverRepository.delete(server);
        }
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
        to.setProductType(from.getProductType());
        to.setServerCount(from.getServerCount());
        to.setTotalPairs(from.getTotalPairs());
        to.setReadyServerCount(from.getReadyServerCount());
        to.setNotReadyServerCount(from.getNotReadyServerCount());
        to.setOpenEscalationCount(from.getOpenEscalationCount());
        to.setMigrationManagers(from.getMigrationManagers());
        to.setMigrationManagerName(from.getMigrationManagerName());
        to.setEngineerEmails(from.getEngineerEmails());
        to.setDevApprovalsDone(from.getDevApprovalsDone());
        to.setDevApprovalsPending(from.getDevApprovalsPending());
        to.setMigrationManagerApprovalsDone(from.getMigrationManagerApprovalsDone());
        to.setMigrationManagerApprovalsPending(from.getMigrationManagerApprovalsPending());
        to.setLastPreCheckSubmittedAt(from.getLastPreCheckSubmittedAt());
        to.setCreatedBy(from.getCreatedBy());
        to.setCreatedAt(from.getCreatedAt());
    }

    private ProjectSummaryDto buildSummary(Project project, List<Server> allServers) {
        List<Server> servers = allServers.stream()
                .filter(s -> s.getProject() != null && s.getProject().getId().equals(project.getId()))
                .toList();

        long totalPairs = servers.stream()
                .mapToLong(s -> workspacePairRepository.findByServerId(s.getId()).size())
                .sum();
        long readyServers = servers.stream().filter(s -> s.getStatus() == PairStatus.DELTA_READY).count();
        long openEscalations = servers.stream()
                .mapToLong(s -> escalationService.countOpenForServer(s.getId()))
                .sum();
        List<String> migrationManagers = project.getMigrationManagerName() != null
                ? List.of(project.getMigrationManagerName())
                : List.of();

        // See DashboardService.getSummary() for why Dev Lead's "pending" also requires Migration
        // Manager to already be approved -- otherwise a not-yet-reached Dev row (created at the same
        // time as Migration Manager's) gets miscounted as an open request.
        long migrationManagerDone = 0;
        long migrationManagerPending = 0;
        long devDone = 0;
        long devPending = 0;
        for (Server s : servers) {
            Optional<SignOffStatus> mmStatus = signOffRepository
                    .findByServerIdAndRole(s.getId(), SignOffRole.MIGRATION_LEAD)
                    .map(SignOff::getStatus);
            Optional<SignOffStatus> devStatus = signOffRepository
                    .findByServerIdAndRole(s.getId(), SignOffRole.DEV_LEAD)
                    .map(SignOff::getStatus);

            if (mmStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
                migrationManagerDone++;
            } else if (mmStatus.filter(status -> status == SignOffStatus.PENDING).isPresent()) {
                migrationManagerPending++;
            }

            if (devStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
                devDone++;
            } else if (devStatus.filter(status -> status == SignOffStatus.PENDING).isPresent()
                    && mmStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
                devPending++;
            }
        }
        LocalDateTime lastPreCheckSubmittedAt = servers.stream()
                .map(s -> preCheckSubmissionRepository.findByServerId(s.getId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(sub -> sub.getStatus() == SubmissionStatus.SUBMITTED)
                .map(PreCheckSubmission::getSubmittedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        ProjectSummaryDto dto = new ProjectSummaryDto();
        dto.setId(project.getId());
        dto.setName(project.getName());
        dto.setProductType(project.getProductType());
        dto.setServerCount(servers.size());
        dto.setTotalPairs(totalPairs);
        dto.setReadyServerCount(readyServers);
        dto.setNotReadyServerCount(servers.size() - readyServers);
        dto.setOpenEscalationCount(openEscalations);
        dto.setMigrationManagers(migrationManagers);
        dto.setMigrationManagerName(project.getMigrationManagerName());
        dto.setEngineerEmails(List.copyOf(project.getEngineerEmails()));
        dto.setDevApprovalsDone(devDone);
        dto.setDevApprovalsPending(devPending);
        dto.setMigrationManagerApprovalsDone(migrationManagerDone);
        dto.setMigrationManagerApprovalsPending(migrationManagerPending);
        dto.setLastPreCheckSubmittedAt(lastPreCheckSubmittedAt);
        dto.setCreatedBy(project.getCreatedBy());
        dto.setCreatedAt(project.getCreatedAt());
        // Ready to decommission once the project has servers and every one of them has Delta finished.
        dto.setDecommissionReady(!servers.isEmpty()
                && servers.stream().allMatch(s -> s.getDeltaFinishedAt() != null));
        return dto;
    }
}
