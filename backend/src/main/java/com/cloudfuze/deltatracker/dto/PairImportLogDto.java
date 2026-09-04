package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.PairImportLog;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One user-mapping CSV upload, for the history panel.
 *
 * <p>The summary leads with what the upload REMOVED when it removed anything. A re-upload replaces
 * the combination's pairs, so "42 rows added" alone hides that 60 went away, and those rows exist
 * nowhere else afterwards.
 */
@Getter
@Setter
@NoArgsConstructor
public class PairImportLogDto {

    private Long id;
    private String combination;
    private String fileName;
    private int totalRows;
    private int createdCount;
    private int updatedCount;
    private int replacedCount;
    private int duplicateCount;
    private int errorCount;
    private String importedBy;
    private AppUserRole importedByRole;
    private String importedByRoleLabel;
    private LocalDateTime importedAt;
    private String summary;

    public static PairImportLogDto fromEntity(PairImportLog log) {
        PairImportLogDto dto = new PairImportLogDto();
        dto.setId(log.getId());
        dto.setCombination(log.getCombination());
        dto.setFileName(log.getFileName());
        dto.setTotalRows(log.getTotalRows());
        dto.setCreatedCount(log.getCreatedCount());
        dto.setUpdatedCount(log.getUpdatedCount());
        dto.setReplacedCount(log.getReplacedCount());
        dto.setDuplicateCount(log.getDuplicateCount());
        dto.setErrorCount(log.getErrorCount());
        dto.setImportedBy(log.getImportedBy());
        dto.setImportedByRole(log.getImportedByRole());
        dto.setImportedByRoleLabel(roleLabel(log.getImportedByRole()));
        dto.setImportedAt(log.getImportedAt());
        dto.setSummary(summarise(log));
        return dto;
    }

    private static String summarise(PairImportLog log) {
        StringBuilder parts = new StringBuilder();
        if (log.getReplacedCount() > 0) {
            // One sentence, not two comma-joined fragments ("replaced 10, 10 added") that read as
            // two separate things happening -- easy to misread as 20 total, when the truth is the
            // old 10 rows are GONE and these are their entire replacement, whether or not the new
            // file happens to contain the same emails as before.
            parts.append("Replaced all ").append(log.getReplacedCount())
                    .append(log.getReplacedCount() == 1 ? " previous pair" : " previous pairs")
                    .append(" with ").append(log.getCreatedCount())
                    .append(log.getCreatedCount() == 1 ? " new pair" : " new pairs");
        } else {
            append(parts, log.getCreatedCount() + " added");
        }
        if (log.getUpdatedCount() > 0) {
            append(parts, log.getUpdatedCount() + " updated");
        }
        if (log.getDuplicateCount() > 0) {
            append(parts, log.getDuplicateCount() + " duplicate skipped");
        }
        if (log.getErrorCount() > 0) {
            append(parts, log.getErrorCount() + " row error" + (log.getErrorCount() == 1 ? "" : "s"));
        }
        return parts.toString();
    }

    private static void append(StringBuilder parts, String text) {
        if (parts.length() > 0) {
            parts.append(", ");
        }
        parts.append(text);
    }

    private static String roleLabel(AppUserRole role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case ADMIN -> "Admin";
            case MIGRATION_MANAGER -> "Migration Manager";
            case DEV_LEAD -> "Dev Lead";
            case QA_LEAD -> "QA Lead";
            case MIGRATION_ENGINEER -> "Migration Engineer";
        };
    }
}
