package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class ServerService {

    public static final String DELTA_TYPE_ITEM = "Delta Type";

    public static final List<String> PRE_CHECK_ITEMS = List.of(
            "OneTime Migration",
            DELTA_TYPE_ITEM,
            "Pre Delta Migration",
            "Data Verified",
            "Permissions Verified",
            "Hyperlinks Verified",
            "Workspace Status Updated in DB",
            "Drive changes"
    );

    private final ServerRepository serverRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final EscalationService escalationService;
    private final ProjectRepository projectRepository;

    public ServerService(ServerRepository serverRepository,
                          WorkspacePairRepository workspacePairRepository,
                          PreCheckItemRepository preCheckItemRepository,
                          PreCheckSubmissionRepository preCheckSubmissionRepository,
                          EscalationService escalationService,
                          ProjectRepository projectRepository) {
        this.serverRepository = serverRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.escalationService = escalationService;
        this.projectRepository = projectRepository;
    }

    public Server findOrThrow(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + id));
    }

    public Server save(Server server) {
        return serverRepository.save(server);
    }

    public void seedPreCheckItems(Server server) {
        for (String itemName : PRE_CHECK_ITEMS) {
            preCheckItemRepository.save(new PreCheckItem(server, itemName));
        }
        preCheckSubmissionRepository.save(new PreCheckSubmission(server));
    }

    public void recomputeStatus(Server server) {
        SubmissionStatus status = preCheckSubmissionRepository.findByServerId(server.getId())
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED);

        boolean anyProgress = status != SubmissionStatus.NOT_STARTED
                || preCheckItemRepository.findByServerId(server.getId()).stream()
                        .anyMatch(item -> item.getStatus() != ItemStatus.NOT_STARTED);

        PairStatus newStatus = status == SubmissionStatus.SUBMITTED
                ? PairStatus.DELTA_READY
                : anyProgress ? PairStatus.IN_PROGRESS : PairStatus.PENDING;

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

    public List<ServerReadinessDto> listReadinessForProject(Long projectId) {
        return serverRepository.findAll().stream()
                .filter(server -> server.getProject() != null && server.getProject().getId().equals(projectId))
                .map(server -> buildReadiness(server, false))
                .toList();
    }

    public ServerReadinessDto getReadiness(Long serverId) {
        Server server = findOrThrow(serverId);
        return buildReadiness(server, true);
    }

    // Post-Delta lifecycle (engineer-driven). Start can only happen after Delta is initiated;
    // Finish only after Start. Timestamps are stamped at click time.
    public ServerReadinessDto startDelta(Long serverId, String actorEmail) {
        Server server = findOrThrow(serverId);
        if (server.getDeltaInitiatedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta hasn't been initiated for this server yet.");
        }
        if (server.getDeltaStartedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta migration has already been started for this server.");
        }
        server.setDeltaStartedAt(LocalDateTime.now());
        server.setDeltaStartedBy(actorEmail);
        return buildReadiness(serverRepository.save(server), true);
    }

    public ServerReadinessDto finishDelta(Long serverId, String actorEmail) {
        Server server = findOrThrow(serverId);
        if (server.getDeltaStartedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start the Delta migration before marking it finished.");
        }
        if (server.getDeltaFinishedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Delta migration is already marked finished for this server.");
        }
        server.setDeltaFinishedAt(LocalDateTime.now());
        server.setDeltaFinishedBy(actorEmail);
        return buildReadiness(serverRepository.save(server), true);
    }

    private ServerReadinessDto buildReadiness(Server server, boolean includePairs) {
        List<WorkspacePair> pairs = workspacePairRepository.findByServerId(server.getId());

        int total = pairs.size();
        int ready = server.getStatus() == PairStatus.DELTA_READY ? total : 0;
        int notReady = total - ready;
        long openEscalations = escalationService.countOpenForServer(server.getId());

        ServerReadinessDto dto = new ServerReadinessDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setStatus(server.getStatus());
        dto.setTotalPairs(total);
        dto.setReadyCount(ready);
        dto.setNotReadyCount(notReady);
        dto.setOpenEscalationCount(openEscalations);
        dto.setReadinessStatus(ServerReadinessDto.computeReadinessStatus(server.getStatus(), openEscalations));
        dto.setDeltaInitiatedAt(server.getDeltaInitiatedAt());
        dto.setDeltaInitiatedBy(server.getDeltaInitiatedBy());
        dto.setDeltaStartedAt(server.getDeltaStartedAt());
        dto.setDeltaStartedBy(server.getDeltaStartedBy());
        dto.setDeltaFinishedAt(server.getDeltaFinishedAt());
        dto.setDeltaFinishedBy(server.getDeltaFinishedBy());
        dto.setSubmissionStatus(preCheckSubmissionRepository.findByServerId(server.getId())
                .map(PreCheckSubmission::getStatus)
                .orElse(SubmissionStatus.NOT_STARTED));
        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
            dto.setMigrationManagerName(server.getProject().getMigrationManagerName());
        }

        if (includePairs) {
            dto.setPairs(pairs.stream().map(WorkspacePairDto::fromEntity).toList());
        }

        return dto;
    }
}
