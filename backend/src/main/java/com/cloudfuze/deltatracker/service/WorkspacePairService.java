package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import com.cloudfuze.deltatracker.util.CsvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final Logger log = LoggerFactory.getLogger(WorkspacePairService.class);

    // Hard cap on how many pairs the display-list endpoint returns in one response. Pairs are
    // CSV-imported and a single server can hold thousands, so the list is bounded (NOT paginated --
    // the response stays a plain array to avoid a UI-breaking shape change). Aggregate/count paths
    // are deliberately left uncapped. Flagged for real pagination in a future pass.
    private static final int MAX_DISPLAY_ROWS = 500;

    // Per-field length caps -- must match the WorkspacePair @Column lengths. Checked per row before
    // save so an oversized cell becomes a "Row N: <field> exceeds maximum length" entry in errors[]
    // like every other row failure, instead of a DataIntegrityViolationException that aborts the
    // whole import with a generic 409.
    private static final int MAX_EMAIL_LEN = 255;
    private static final int MAX_PATH_LEN = 1000;
    private static final int MAX_COMBINATION_LEN = 200;

    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "server_url", List.of("serverurl", "url", "server"),
            "source_email", List.of("sourceemail", "source", "sourceuser", "sourceaccount"),
            "source_path", List.of("sourcepath", "sourcefolder", "sourcefolderpath"),
            "destination_email", List.of("destinationemail", "destination", "destinationuser", "targetemail", "destinationaccount"),
            "destination_path", List.of("destinationpath", "destinationfolder", "targetpath", "destinationfolderpath"),
            "combination", List.of("combination", "platformcombination", "sourcedestinationtype")
    );

    private static final List<String> REQUIRED_COLUMNS = List.of("source_email", "destination_email");
    private static final List<String> REQUIRED_COLUMNS_GLOBAL = List.of("server_url", "source_email", "destination_email");

    private final WorkspacePairRepository workspacePairRepository;
    private final ServerRepository serverRepository;
    private final WorkspaceCombinationService workspaceCombinationService;
    private final ProjectRepository projectRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final ServerPurgeService serverPurgeService;
    private final ServerService serverService;
    private final TeamService teamService;

    public WorkspacePairService(WorkspacePairRepository workspacePairRepository,
                                 ServerRepository serverRepository,
                                 WorkspaceCombinationService workspaceCombinationService,
                                 ProjectRepository projectRepository,
                                 WorkspaceCombinationRepository workspaceCombinationRepository,
                                 ServerPurgeService serverPurgeService,
                                 ServerService serverService,
                                 TeamService teamService) {
        this.workspacePairRepository = workspacePairRepository;
        this.serverRepository = serverRepository;
        this.workspaceCombinationService = workspaceCombinationService;
        this.projectRepository = projectRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.serverPurgeService = serverPurgeService;
        this.serverService = serverService;
        this.teamService = teamService;
    }

    public List<WorkspacePairDto> listByServer(Long serverId) {
        long total = workspacePairRepository.countByServerId(serverId);
        if (total > MAX_DISPLAY_ROWS) {
            log.warn("Workspace pairs for server {} exceed the display cap ({} > {}); returning the first {} "
                            + "by id. This endpoint needs real pagination.",
                    serverId, total, MAX_DISPLAY_ROWS, MAX_DISPLAY_ROWS);
        }
        return workspacePairRepository
                .findByServerId(serverId, PageRequest.of(0, MAX_DISPLAY_ROWS, Sort.by(Sort.Direction.ASC, "id")))
                .stream()
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
        List<String> duplicates = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int duplicate = 0;

        for (int rowNum = 1; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> fields = CsvUtils.parseLine(line);
            switch (processRow(server, fields, columnIndex, rowNum, errors, duplicates, null)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case DUPLICATE -> duplicate++;
                case SKIPPED -> { /* reason already recorded in errors */ }
            }
        }

        server.setTotalPairCount((int) workspacePairRepository.countByServerId(server.getId()));
        serverRepository.save(server);

        result.setTotalRows(lines.size() - 1);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setDuplicateCount(duplicate);
        result.setDuplicates(duplicates);
        result.setErrors(errors);
        return result;
    }

    // Variant of importCsv for the "add a Server URL, then add a combination, then upload its CSV"
    // flow on the project page: the server and combination are both already chosen in the UI
    // before the file is picked, so the CSV itself only carries source/destination email + path
    // (no server_url or combination column) -- every row gets the same, caller-supplied
    // combination stamped on it via processRow's override, instead of reading one per row.
    public WorkspacePairImportResultDto importCsvForServerCombination(Long serverId, String combination,
                                                                       MultipartFile file) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + serverId));
        if (!StringUtils.hasText(combination)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "combination is required");
        }
        String trimmedCombination = combination.trim();

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
        List<String> duplicates = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int duplicate = 0;

        // A re-upload REPLACES this combination's pairs -- the file is the new complete list, so a
        // row that is no longer in it must no longer be in the app. Merging left rows behind that the
        // uploader had deliberately removed, with nothing on screen to say so.
        //
        // Deleted only after the header and required columns have been validated above, so a file
        // that was never going to import cannot empty the list on its way to an error. Safe to
        // delete outright because a WorkspacePair carries no state of its own beyond the row --
        // source/destination email and path, and the combination name.
        List<WorkspacePair> existing =
                workspacePairRepository.findByServerIdAndCombinationIgnoreCase(server.getId(), trimmedCombination);
        int replaced = existing.size();
        if (!existing.isEmpty()) {
            workspacePairRepository.deleteAll(existing);
            // Flushed so the duplicate check inside processRow sees the rows as gone; otherwise every
            // re-uploaded row would come back as a duplicate of the one it is replacing.
            workspacePairRepository.flush();
        }

        for (int rowNum = 1; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> fields = CsvUtils.parseLine(line);
            switch (processRow(server, fields, columnIndex, rowNum, errors, duplicates, trimmedCombination)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case DUPLICATE -> duplicate++;
                case SKIPPED -> { /* reason already recorded in errors */ }
            }
        }

        // Nothing usable in the file, but rows were deleted to make room for it: refuse the whole
        // thing so the transaction rolls back and the old list survives. Without this, re-uploading
        // a malformed file would silently leave the combination with no pairs at all.
        if (created == 0 && replaced > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No usable rows in this CSV, so the existing " + replaced + " pair"
                            + (replaced == 1 ? "" : "s") + " were kept. "
                            + (errors.isEmpty() ? "The file has a header but no data rows." : errors.get(0)));
        }

        server.setTotalPairCount((int) workspacePairRepository.countByServerId(server.getId()));
        serverRepository.save(server);

        result.setTotalRows(lines.size() - 1);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setReplacedCount(replaced);
        result.setDuplicateCount(duplicate);
        result.setDuplicates(duplicates);
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
            boolean isTeamMember = teamService.isCurrentlyOnManagersTeam(project.getMigrationManagerName(), callerEmail);
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
        List<String> duplicates = new ArrayList<>();
        Map<String, Server> serverCache = new HashMap<>();
        int created = 0;
        int updated = 0;
        int duplicate = 0;

        for (int rowNum = 1; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> fields = CsvUtils.parseLine(line);

            String serverUrl = valueOf(fields, columnIndex, "server_url");
            if (!StringUtils.hasText(serverUrl)) {
                errors.add("Row " + (rowNum + 1) + ": server_url is required");
                continue;
            }

            Server server = serverCache.computeIfAbsent(serverUrl.trim().toLowerCase(),
                    key -> serverRepository.findByProjectIdAndNameIgnoreCase(project.getId(), serverUrl.trim())
                            .orElseGet(() -> {
                                Server newServer = new Server(serverUrl.trim());
                                newServer.setProject(project);
                                return serverRepository.save(newServer);
                            }));

            switch (processRow(server, fields, columnIndex, rowNum, errors, duplicates, null)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case DUPLICATE -> duplicate++;
                case SKIPPED -> { /* reason already recorded in errors */ }
            }
        }

        for (Server server : serverCache.values()) {
            server.setTotalPairCount((int) workspacePairRepository.countByServerId(server.getId()));
            serverRepository.save(server);
        }

        result.setTotalRows(lines.size() - 1);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setDuplicateCount(duplicate);
        result.setDuplicates(duplicates);
        result.setErrors(errors);
        return result;
    }

    // Deletes every pair under this combination for this server -- the "trash" action on a
    // combination row in the project page's per-server list. Case-insensitive to match how
    // processRow's duplicate check already treats combination values.
    /**
     * Deletes one combination on a server: its pairs AND the combination itself.
     *
     * <p>This previously deleted only the WorkspacePair rows. The UI lists a server's combinations
     * from the WorkspaceCombination table, not from its pairs, so the combination stayed on screen
     * after a "successful" delete and looked undeletable -- while its checklist, sign-offs, tickets
     * and Delta cycles were left behind as unreachable rows, and its evidence files stranded on
     * disk. Reuses ServerPurgeService so a combination is torn down by exactly the same rules
     * whether it is deleted on its own or as part of its whole server.
     *
     * <p>Admin-only, enforced in SecurityConfig. No Delta-initiated guard on purpose: that matches
     * the existing admin override for deleting a project whose Delta has started.
     */
    public void deleteByServerAndCombination(Long serverId, String combination) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + serverId));

        List<WorkspacePair> pairs = workspacePairRepository.findByServerIdAndCombinationIgnoreCase(serverId, combination);
        workspacePairRepository.deleteAll(pairs);

        // Matched by name, case-insensitively, the same way pairs are tied to their combination.
        workspaceCombinationRepository.findByServerIdAndNameIgnoreCase(serverId, combination)
                .ifPresent(serverPurgeService::purgeCombination);

        server.setTotalPairCount((int) workspacePairRepository.countByServerId(server.getId()));
        Server saved = serverRepository.save(server);
        // The server's status is a rollup of its combinations' statuses, so removing one can change
        // it -- e.g. the last not-yet-ready combination going away makes the server DELTA_READY.
        serverService.recomputeStatus(saved);
    }

    private enum RowOutcome { CREATED, UPDATED, DUPLICATE, SKIPPED }

    /**
     * Processes a single CSV data row against the given server:
     * <ul>
     *   <li>{@code SKIPPED} — the row was invalid; the reason is appended to {@code errors}.</li>
     *   <li>{@code DUPLICATE} — an identical pair (same source/destination email + path AND the same
     *       combination) already exists for this server; nothing is written and a human-readable line
     *       is appended to {@code duplicates}. This is what lets a re-uploaded file report "already
     *       imported" instead of silently doing nothing.</li>
     *   <li>{@code CREATED} — a brand-new pair was inserted. Combination is part of a pair's identity,
     *       so the same source/destination email+path under a *different* combination is a distinct
     *       pair, not an update of the existing one -- e.g. the same person's Box account and Google
     *       Drive account can both be migrating to the same OneDrive mailbox as two separate
     *       combinations.</li>
     * </ul>
     *
     * @param combinationOverride when non-null, used as every row's combination instead of reading
     *                            a "combination" column from the CSV -- see
     *                            {@link #importCsvForServerCombination}, where the combination is
     *                            chosen once in the UI rather than being a per-row CSV value.
     */
    private RowOutcome processRow(Server server, List<String> fields, Map<String, Integer> columnIndex,
                                  int rowNum, List<String> errors, List<String> duplicates,
                                  String combinationOverride) {
        String sourceEmail = valueOf(fields, columnIndex, "source_email");
        String destinationEmail = valueOf(fields, columnIndex, "destination_email");
        if (!StringUtils.hasText(sourceEmail) || !StringUtils.hasText(destinationEmail)) {
            errors.add("Row " + (rowNum + 1) + ": source_email and destination_email are required");
            return RowOutcome.SKIPPED;
        }

        String sourcePath = orEmpty(valueOf(fields, columnIndex, "source_path"));
        String destinationPath = orEmpty(valueOf(fields, columnIndex, "destination_path"));
        String combination = combinationOverride != null ? combinationOverride : valueOf(fields, columnIndex, "combination");

        String overLong = firstOverLengthField(sourceEmail, destinationEmail, sourcePath, destinationPath, combination);
        if (overLong != null) {
            errors.add("Row " + (rowNum + 1) + ": " + overLong + " exceeds maximum length");
            return RowOutcome.SKIPPED;
        }

        // A combination row (checklist/sign-off/Delta lifecycle) is only meaningful once it has a
        // real name -- get-or-create it now so it's guaranteed to exist for every valid row under
        // this name, whether the pair itself turns out to be new or a duplicate.
        if (StringUtils.hasText(combination)) {
            workspaceCombinationService.getOrCreate(server, combination);
        }

        boolean alreadyExists = workspacePairRepository
                .findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPathAndCombination(
                        server.getId(), sourceEmail, sourcePath, destinationEmail, destinationPath, combination)
                .isPresent();

        if (alreadyExists) {
            duplicates.add("Row " + (rowNum + 1) + ": " + sourceEmail + " \u2192 " + destinationEmail
                    + " already exists (skipped)");
            return RowOutcome.DUPLICATE;
        }

        WorkspacePair pair = new WorkspacePair(server, sourceEmail, destinationEmail);
        pair.setSourcePath(sourcePath);
        pair.setDestinationPath(destinationPath);
        pair.setCombination(combination);
        workspacePairRepository.save(pair);
        return RowOutcome.CREATED;
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

    // Returns the name of the first field whose value exceeds its column length, or null if all fit.
    private String firstOverLengthField(String sourceEmail, String destinationEmail, String sourcePath,
                                        String destinationPath, String combination) {
        if (over(sourceEmail, MAX_EMAIL_LEN)) return "source_email";
        if (over(destinationEmail, MAX_EMAIL_LEN)) return "destination_email";
        if (over(sourcePath, MAX_PATH_LEN)) return "source_path";
        if (over(destinationPath, MAX_PATH_LEN)) return "destination_path";
        if (over(combination, MAX_COMBINATION_LEN)) return "combination";
        return null;
    }

    private boolean over(String value, int max) {
        return value != null && value.length() > max;
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
