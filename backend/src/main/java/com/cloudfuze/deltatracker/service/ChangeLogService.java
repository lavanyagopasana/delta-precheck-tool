package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ChangeLogEntryDto;
import com.cloudfuze.deltatracker.dto.PairImportLogDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.ChangeLogEntityType;
import com.cloudfuze.deltatracker.entity.ChangeLogEntry;
import com.cloudfuze.deltatracker.entity.PairImportLog;
import com.cloudfuze.deltatracker.repository.ChangeLogEntryRepository;
import com.cloudfuze.deltatracker.repository.PairImportLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Records field-level edits to projects, servers and combinations.
 *
 * <p>One service so every caller records the same way: same "did it actually change" rule, same
 * truncation, same role-at-the-time capture. The alternative was each service hand-rolling its own
 * rows, which is how two trails end up disagreeing about what counts as a change.
 */
@Service
@Transactional
public class ChangeLogService {

    // Matches the entity's column length. Truncated rather than rejected: losing the tail of a long
    // old value is a cosmetic loss, while failing the edit over its audit row would not be.
    private static final int MAX_VALUE_LENGTH = 1000;

    private final ChangeLogEntryRepository repository;
    private final PairImportLogRepository pairImportLogRepository;
    private final AppUserService appUserService;

    public ChangeLogService(ChangeLogEntryRepository repository,
                             PairImportLogRepository pairImportLogRepository,
                             AppUserService appUserService) {
        this.repository = repository;
        this.pairImportLogRepository = pairImportLogRepository;
        this.appUserService = appUserService;
    }

    /**
     * Records one field change, if the value actually changed.
     *
     * <p>Null and blank are treated as the same absence, so clearing an already-empty field is not a
     * change. Values are compared as they will be displayed, which is what a reader of the trail is
     * checking against.
     */
    public void record(ChangeLogEntityType entityType, Long entityId, String fieldName,
                        String oldValue, String newValue, String changedBy) {
        String before = normalise(oldValue);
        String after = normalise(newValue);
        if (Objects.equals(before, after)) {
            return;
        }
        repository.save(new ChangeLogEntry(entityType, entityId, fieldName,
                truncate(before), truncate(after),
                changedBy == null || changedBy.isBlank() ? null : changedBy.trim(),
                // Resolved now, not when the trail is read: a later role change must not rewrite it.
                appUserService.roleOf(changedBy).orElse(null)));
    }

    @Transactional(readOnly = true)
    public List<ChangeLogEntry> history(ChangeLogEntityType entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByChangedAtDescIdDesc(entityType, entityId);
    }

    /** The same trail, mapped for the API. Newest first. */
    @Transactional(readOnly = true)
    public List<ChangeLogEntryDto> historyDtos(ChangeLogEntityType entityType, Long entityId) {
        return history(entityType, entityId).stream().map(ChangeLogEntryDto::fromEntity).toList();
    }

    /**
     * Records one user-mapping CSV upload.
     *
     * <p>Takes the already-computed result rather than re-counting: the import is the only thing that
     * knows how many rows it replaced, and that figure cannot be recovered from anywhere else once
     * the old pairs are gone.
     */
    public void recordPairImport(Long serverId, String combination, String fileName,
                                  WorkspacePairImportResultDto result, String importedBy) {
        PairImportLog log = new PairImportLog();
        log.setServerId(serverId);
        log.setCombination(combination == null || combination.isBlank() ? null : combination.trim());
        log.setFileName(fileName);
        log.setTotalRows(result.getTotalRows());
        log.setCreatedCount(result.getCreatedCount());
        log.setUpdatedCount(result.getUpdatedCount());
        log.setReplacedCount(result.getReplacedCount());
        log.setDuplicateCount(result.getDuplicateCount());
        log.setErrorCount(result.getErrors() == null ? 0 : result.getErrors().size());
        log.setImportedBy(importedBy == null || importedBy.isBlank() ? null : importedBy.trim());
        log.setImportedByRole(appUserService.roleOf(importedBy).orElse(null));
        pairImportLogRepository.save(log);
    }

    /**
     * Records that an entity was deleted. Uses the same "Deleted" field name for every entity type,
     * so {@link #recentlyDeleted} can find them all with one query regardless of what was deleted.
     *
     * <p>Called BEFORE the row itself is removed -- the caller still has the name/identity to put in
     * oldValue, which is gone the instant the delete happens.
     */
    public void recordDeletion(ChangeLogEntityType entityType, Long entityId, String identity,
                                String deletedBy) {
        record(entityType, entityId, "Deleted", identity, null, deletedBy);
    }

    /** Every recorded deletion of one entity type, newest first -- e.g. every project ever deleted. */
    @Transactional(readOnly = true)
    public List<ChangeLogEntryDto> recentlyDeleted(ChangeLogEntityType entityType) {
        return repository.findByEntityTypeAndFieldNameOrderByChangedAtDescIdDesc(entityType, "Deleted").stream()
                .map(ChangeLogEntryDto::fromEntity)
                .toList();
    }

    /** Every upload against one server, newest first. */
    @Transactional(readOnly = true)
    public List<PairImportLogDto> pairImportHistory(Long serverId) {
        return pairImportLogRepository.findByServerIdOrderByImportedAtDescIdDesc(serverId).stream()
                .map(PairImportLogDto::fromEntity)
                .toList();
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH);
    }
}
