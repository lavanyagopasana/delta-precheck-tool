package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import com.cloudfuze.deltatracker.util.CsvUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class WorkspacePairService {

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "server_name", List.of("servername", "server"),
            "source_email", List.of("sourceemail", "source", "sourceuser", "sourceaccount"),
            "source_path", List.of("sourcepath", "sourcefolder", "sourcefolderpath"),
            "destination_email", List.of("destinationemail", "destination", "destinationuser", "targetemail", "destinationaccount"),
            "destination_path", List.of("destinationpath", "destinationfolder", "targetpath", "destinationfolderpath"),
            "combination", List.of("combination", "platformcombination", "sourcedestinationtype")
    );

    private static final List<String> REQUIRED_COLUMNS = List.of("source_email", "destination_email");
    private static final List<String> REQUIRED_COLUMNS_GLOBAL = List.of("server_name", "source_email", "destination_email");

    private final WorkspacePairRepository workspacePairRepository;
    private final ServerRepository serverRepository;
    private final ServerService serverService;
    private final ProjectRepository projectRepository;

    public WorkspacePairService(WorkspacePairRepository workspacePairRepository,
                                 ServerRepository serverRepository,
                                 ServerService serverService,
                                 ProjectRepository projectRepository) {
        this.workspacePairRepository = workspacePairRepository;
        this.serverRepository = serverRepository;
        this.serverService = serverService;
        this.projectRepository = projectRepository;
    }

    public List<WorkspacePairDto> listByServer(Long serverId) {
        return workspacePairRepository.findByServerId(serverId).stream()
                .map(WorkspacePairDto::fromEntity)
                .toList();
    }

    public WorkspacePairDto get(Long id) {
        return WorkspacePairDto.fromEntity(findOrThrow(id));
    }

    public WorkspacePair findOrThrow(Long id) {
        return workspacePairRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace pair not found: " + id));
    }

    public WorkspacePairImportResultDto importCsv(Long serverId, MultipartFile file) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + serverId));

        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }

        Map<String, Integer> columnIndex = resolveColumns(CsvUtils.parseLine(lines.get(0)));
        for (String required : REQUIRED_COLUMNS) {
            if (!columnIndex.containsKey(required)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CSV is missing required column: " + required);
            }
        }

        WorkspacePairImportResultDto result = new WorkspacePairImportResultDto();
        List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int rowNum = 1; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> fields = CsvUtils.parseLine(line);
            Boolean isNew = processRow(server, fields, columnIndex, rowNum, errors);
            if (isNew == null) {
                continue;
            }
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        server.setTotalPairCount(workspacePairRepository.findByServerId(server.getId()).size());
        serverRepository.save(server);

        result.setTotalRows(lines.size() - 1);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setErrors(errors);
        return result;
    }

    public WorkspacePairImportResultDto importCsvGlobal(MultipartFile file, Long projectId, String callerEmail,
                                                          boolean isAdmin) {
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select a project before importing this CSV.");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        if (callerEmail != null && !isAdmin) {
            boolean isManager = callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
            boolean isTeamMember = project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
            if (!isManager && !isTeamMember) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Only this project's Migration Manager or team members can import a CSV here.");
            }
        }

        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }

        Map<String, Integer> columnIndex = resolveColumns(CsvUtils.parseLine(lines.get(0)));
        for (String required : REQUIRED_COLUMNS_GLOBAL) {
            if (!columnIndex.containsKey(required)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CSV is missing required column: " + required);
            }
        }

        WorkspacePairImportResultDto result = new WorkspacePairImportResultDto();
        List<String> errors = new ArrayList<>();
        Map<String, Server> serverCache = new HashMap<>();
        int created = 0;
        int updated = 0;

        for (int rowNum = 1; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> fields = CsvUtils.parseLine(line);

            String serverName = valueOf(fields, columnIndex, "server_name");
            if (!StringUtils.hasText(serverName)) {
                errors.add("Row " + (rowNum + 1) + ": server_name is required");
                continue;
            }

            Server server = serverCache.computeIfAbsent(serverName.trim().toLowerCase(),
                    key -> serverRepository.findByProjectIdAndNameIgnoreCase(project.getId(), serverName.trim())
                            .orElseGet(() -> {
                                Server newServer = new Server(serverName.trim());
                                newServer.setProject(project);
                                newServer = serverRepository.save(newServer);
                                serverService.seedPreCheckItems(newServer);
                                return newServer;
                            }));

            Boolean isNew = processRow(server, fields, columnIndex, rowNum, errors);
            if (isNew == null) {
                continue;
            }
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        for (Server server : serverCache.values()) {
            server.setTotalPairCount(workspacePairRepository.findByServerId(server.getId()).size());
            serverRepository.save(server);
        }

        result.setTotalRows(lines.size() - 1);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setErrors(errors);
        return result;
    }

    /**
     * Processes a single CSV data row against the given server. Returns true if a new pair was
     * created, false if an existing pair was updated, or null if the row was invalid and skipped
     * (with the reason appended to {@code errors}).
     */
    private Boolean processRow(Server server, List<String> fields, Map<String, Integer> columnIndex,
                                int rowNum, List<String> errors) {
        String sourceEmail = valueOf(fields, columnIndex, "source_email");
        String destinationEmail = valueOf(fields, columnIndex, "destination_email");
        if (!StringUtils.hasText(sourceEmail) || !StringUtils.hasText(destinationEmail)) {
            errors.add("Row " + (rowNum + 1) + ": source_email and destination_email are required");
            return null;
        }

        String sourcePath = orEmpty(valueOf(fields, columnIndex, "source_path"));
        String destinationPath = orEmpty(valueOf(fields, columnIndex, "destination_path"));
        String combination = valueOf(fields, columnIndex, "combination");

        WorkspacePair pair = workspacePairRepository
                .findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPath(
                        server.getId(), sourceEmail, sourcePath, destinationEmail, destinationPath)
                .orElse(null);

        boolean isNew = pair == null;
        if (isNew) {
            pair = new WorkspacePair(server, sourceEmail, destinationEmail);
        }
        pair.setSourcePath(sourcePath);
        pair.setDestinationPath(destinationPath);
        pair.setCombination(combination);
        workspacePairRepository.save(pair);

        return isNew;
    }

    private Map<String, Integer> resolveColumns(List<String> header) {
        Map<String, Integer> columnIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalizeHeader(header.get(i));
            for (Map.Entry<String, List<String>> entry : COLUMN_ALIASES.entrySet()) {
                if (entry.getValue().contains(normalized) && !columnIndex.containsKey(entry.getKey())) {
                    columnIndex.put(entry.getKey(), i);
                }
            }
        }
        return columnIndex;
    }

    private String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        // Strip a leading UTF-8 BOM that Excel sometimes writes into the first cell.
        String cleaned = header.replace("﻿", "");
        return cleaned.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String valueOf(List<String> fields, Map<String, Integer> columnIndex, String column) {
        Integer index = columnIndex.get(column);
        if (index == null || index >= fields.size()) {
            return null;
        }
        String value = fields.get(index);
        return StringUtils.hasText(value) ? value : null;
    }

    private List<String> readLines(MultipartFile file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read CSV file");
        }
        return lines;
    }
}
