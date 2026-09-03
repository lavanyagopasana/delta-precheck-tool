package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ChangeLogEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One entry in a project's, server's or combination's edit history.
 *
 * <p>Carries a ready-made {@code summary} alongside the raw values, so every screen words a change
 * the same way. Blank old/new values are rendered as "empty" here rather than in each caller --
 * "Name: empty → Acme" reads better than a gap where a value should be.
 */
@Getter
@Setter
@NoArgsConstructor
public class ChangeLogEntryDto {

    private Long id;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String changedBy;
    private AppUserRole changedByRole;
    private String changedByRoleLabel;
    private LocalDateTime changedAt;
    private String summary;

    public static ChangeLogEntryDto fromEntity(ChangeLogEntry entry) {
        ChangeLogEntryDto dto = new ChangeLogEntryDto();
        dto.setId(entry.getId());
        dto.setFieldName(entry.getFieldName());
        dto.setOldValue(entry.getOldValue());
        dto.setNewValue(entry.getNewValue());
        dto.setChangedBy(entry.getChangedBy());
        dto.setChangedByRole(entry.getChangedByRole());
        dto.setChangedByRoleLabel(roleLabel(entry.getChangedByRole()));
        dto.setChangedAt(entry.getChangedAt());
        dto.setSummary(entry.getFieldName() + ": "
                + display(entry.getOldValue()) + " → " + display(entry.getNewValue()));
        return dto;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "empty" : value;
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
