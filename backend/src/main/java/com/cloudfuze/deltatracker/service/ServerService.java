package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.CombinationSummaryDto;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class ServerService {

    public static final String DELTA_TYPE_ITEM = "Delta Type";
    // Only required/counted when Delta Type's own status is PRE_DELTA -- see
    // PreCheckSubmissionService.isPreDeltaMigrationRequired.
    public static final String PRE_DELTA_MIGRATION_ITEM = "Pre Delta Migration";

    public static final List<String> PRE_CHECK_ITEMS = List.of(
            "OneTime Migration",
            DELTA_TYPE_ITEM,
            PRE_DELTA_MIGRATION_ITEM,
            "Data Verified",
            "Permissions Verified",
            "Hyperlinks Verified",
            "Workspace Status Updated in DB",
            "Drive changes"
    );

    private final ServerRepository serverRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final TicketService ticketService;
    private final ProjectRepository projectRepository;

    public ServerService(ServerRepository serverRepository,
                          WorkspacePairRepository workspacePairRepository,
                          WorkspaceCombinationRepository workspaceCombinationRepository,
                          PreCheckSubmissionRepository preCheckSubmissionRepository,
                          TicketService ticketService,
                          ProjectRepository projectRepository) {
        this.serverRepository = serverRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.ticketService = ticketService;
        this.projectRepository = projectRepository;
    }

    public Server findOrThrow(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + id));
    }

    public Server save(Server server) {
        return serverRepository.save(server);
    }

    // Creates a Server directly under a project (the "Server URL" add flow on the project page),
    // without requiring a CSV import first. Mirrors WorkspacePairService.importCsvGlobal's
    // new-server path: same case-insensitive per-project uniqueness rule, the same per-project
    // permission check (non-admins must be this project's Migration Manager or a team member). No
    // pre-check seeding happens here anymore -- that's per-combination now (see
    // WorkspaceCombinationService), and a freshly created server has no combinations yet.
    public ServerReadinessDto createForProject(Long projectId, String name, String callerEmail, boolean isAdmin) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        if (callerEmail != null && !isAdmin) {
            boolean isManager = callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
            boolean isTeamMember = project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
            if (!isManager && !isTeamMember) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Only this project's Migration Manager or team members can add a server here.");
            }
        }

        String trimmed = name.trim();
        if (serverRepository.findByProjectIdAndNameIgnoreCase(projectId, trimmed).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "A server with this URL already exists in this project.");
        }
        Server server = new Server(trimmed);
        server.setProject(project);
        server = serverRepository.save(server);
        return buildReadiness(server, false);
    }

    // Server.status is a rollup of its combinations' own statuses -- DELTA_READY only once the
    // server has at least one combination AND every one of them is DELTA_READY; PENDING if none of
    // them have any progress; IN_PROGRESS otherwise. Called whenever a combination's own status
    // changes (WorkspaceCombinationService.recomputeStatus).
    public void recomputeStatus(Server server) {
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getId());

        PairStatus newStatus;
        if (combinations.isEmpty()) {
            newStatus = PairStatus.PENDING;
        } else if (combinations.stream().allMatch(c -> c.getStatus() == PairStatus.DELTA_READY)) {
            newStatus = PairStatus.DELTA_READY;
        } else if (combinations.stream().anyMatch(c -> c.getStatus() != PairStatus.PENDING)) {
            newStatus = PairStatus.IN_PROGRESS;
        } else {
            newStatus = PairStatus.PENDING;
        }

        server.setStatus(newStatus);
        serverRepository.save(server);
    }

    public ServerReadinessDto assignProject(Long serverId, Long projectId) {
        Server server = findOrThrow(serverId);
        if (projectId == null) {
            server.setProject(null);
        } else {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
            server.setProject(project);
        }
        Server saved = serverRepository.save(server);
        return buildReadiness(saved, false);
    }

    public List<ServerReadinessDto> listReadiness() {
        return serverRepository.findAll().stream()
                .map(server -> buildReadiness(server, false))
                .toList();
    }

    // includePairs=true here (unlike listReadiness/assignProject) so the project page's "Servers &
    // Migration Pairs" card can derive each server's already-uploaded combinations without a
    // separate round trip per server.
    public List<ServerReadinessDto> listReadinessForProject(Long projectId) {
        return serverRepository.findAll().stream()
                .filter(server -> server.getProject() != null && server.getProject().getId().equals(projectId))
                .map(server -> buildReadiness(server, true))
                .toList();
    }

    public ServerReadinessDto getReadiness(Long serverId) {
        Server server = findOrThrow(serverId);
        return buildReadiness(server, true);
    }

    private ServerReadinessDto buildReadiness(Server server, boolean includePairs) {
        List<WorkspacePair> pairs = workspacePairRepository.findByServerId(server.getId());
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getId());

        int total = pairs.size();
        int ready = server.getStatus() == PairStatus.DELTA_READY ? total : 0;
        int notReady = total - ready;
        long openEscalations = ticketService.countOpenForServer(server.getId());

        ServerReadinessDto dto = new ServerReadinessDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setStatus(server.getStatus());
        dto.setTotalPairs(total);
        dto.setReadyCount(ready);
        dto.setNotReadyCount(notReady);
        dto.setOpenEscalationCount(openEscalations);
        dto.setReadinessStatus(ServerReadinessDto.computeReadinessStatus(server.getStatus(), openEscalations));
        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
            dto.setMigrationManagerName(server.getProject().getMigrationManagerName());
            dto.setProductType(server.getProject().getProductType());
        }

        List<Long> combinationIds = combinations.stream().map(WorkspaceCombination::getId).toList();
        Map<Long, PreCheckSubmission> submissionByCombination = preCheckSubmissionRepository
                .findByCombinationIdIn(combinationIds).stream()
                .collect(Collectors.toMap(s -> s.getCombination().getId(), s -> s));

        dto.setCombinations(combinations.stream()
                .map(c -> {
                    long pairCount = pairs.stream().filter(p -> sameCombination(p.getCombination(), c.getName())).count();
                    CombinationSummaryDto summary = new CombinationSummaryDto();
                    summary.setId(c.getId());
                    summary.setName(c.getName());
                    summary.setPairCount((int) pairCount);
                    summary.setStatus(c.getStatus());
                    summary.setSubmissionStatus(java.util.Optional.ofNullable(submissionByCombination.get(c.getId()))
                            .map(PreCheckSubmission::getStatus)
                            .orElse(SubmissionStatus.NOT_STARTED));
                    return summary;
                })
                .toList());

        if (includePairs) {
            dto.setPairs(pairs.stream().map(WorkspacePairDto::fromEntity).toList());
        }

        return dto;
    }

    private boolean sameCombination(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }
}
