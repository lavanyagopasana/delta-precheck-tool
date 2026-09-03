package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItemEdit;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One entry in a pre-check item's edit trail.
 *
 * <p>Carries a ready-made {@code summary} as well as the raw fields. The wording of "changed the
 * note" versus "replaced 2 files" is the same on every screen that shows a trail, and deriving it
 * once here beats each caller re-deriving it slightly differently.
 */
@Getter
@Setter
@NoArgsConstructor
public class PreCheckItemEditDto {

    private Long id;
    /** Which item was edited -- the trail is read per form, not per item. */
    private String itemName;
    private String editedBy;
    private AppUserRole editedByRole;
    private String editedByRoleLabel;
    private LocalDateTime editedAt;
    private ItemStatus fromStatus;
    private ItemStatus toStatus;
    private boolean notesChanged;
    private int evidenceAdded;
    private int evidenceRemoved;
    private String summary;

    public static PreCheckItemEditDto fromEntity(PreCheckItemEdit edit) {
        PreCheckItemEditDto dto = new PreCheckItemEditDto();
        dto.setId(edit.getId());
        dto.setItemName(edit.getItem() == null ? null : edit.getItem().getItemName());
        dto.setEditedBy(edit.getEditedBy());
        dto.setEditedByRole(edit.getEditedByRole());
        dto.setEditedByRoleLabel(roleLabel(edit.getEditedByRole()));
        dto.setEditedAt(edit.getEditedAt());
        dto.setFromStatus(edit.getFromStatus());
        dto.setToStatus(edit.getToStatus());
        dto.setNotesChanged(edit.isNotesChanged());
        dto.setEvidenceAdded(edit.getEvidenceAdded());
        dto.setEvidenceRemoved(edit.getEvidenceRemoved());
        dto.setSummary(summarise(edit));
        return dto;
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

    private static String summarise(PreCheckItemEdit edit) {
        StringBuilder parts = new StringBuilder();
        if (edit.getFromStatus() != edit.getToStatus()) {
            // The from-status is included deliberately: "set to Completed" hides that it had already
            // been Completed and was walked back and forth.
            parts.append("status ")
                    .append(edit.getFromStatus() == null ? "unset" : edit.getFromStatus())
                    .append(" → ")
                    .append(edit.getToStatus() == null ? "unset" : edit.getToStatus());
        }
        if (edit.isNotesChanged()) {
            append(parts, "note edited");
        }
        if (edit.getEvidenceAdded() > 0) {
            append(parts, edit.getEvidenceAdded() + " file" + (edit.getEvidenceAdded() == 1 ? "" : "s") + " added");
        }
        if (edit.getEvidenceRemoved() > 0) {
            append(parts, edit.getEvidenceRemoved() + " file" + (edit.getEvidenceRemoved() == 1 ? "" : "s") + " removed");
        }
        return parts.length() == 0 ? "edited" : parts.toString();
    }

    private static void append(StringBuilder parts, String text) {
        if (parts.length() > 0) {
            parts.append(", ");
        }
        parts.append(text);
    }
}
