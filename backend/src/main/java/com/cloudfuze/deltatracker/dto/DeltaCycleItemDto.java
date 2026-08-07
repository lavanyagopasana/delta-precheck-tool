package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// One frozen checklist row inside a past cycle's snapshot. Read-only by nature -- there's no
// *Request counterpart because a snapshot is never edited.
@Getter
@Setter
public class DeltaCycleItemDto {

    private Long id;
    private String itemName;
    private ItemStatus status;
    private String notes;
    private String evidenceFilePath;
    private String evidenceFileName;
    private String lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    public static DeltaCycleItemDto fromEntity(DeltaCycleItem item) {
        DeltaCycleItemDto dto = new DeltaCycleItemDto();
        dto.setId(item.getId());
        dto.setItemName(item.getItemName());
        dto.setStatus(item.getStatus());
        dto.setNotes(item.getNotes());
        dto.setEvidenceFilePath(item.getEvidenceFilePath());
        dto.setEvidenceFileName(item.getEvidenceFileName());
        dto.setLastModifiedBy(item.getLastModifiedBy());
        dto.setLastModifiedAt(item.getLastModifiedAt());
        return dto;
    }
}
