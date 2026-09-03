package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.ItemStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// One frozen checklist row inside a past cycle's snapshot. Read-only by nature -- there's no
// *Request counterpart because a snapshot is never edited.
@Getter
@Setter
public class DeltaCycleItemDto {

    private Long id;
    private String itemName;
    private ItemStatus status;
    private String notes;
    /** The first frozen file. Kept because the history table has always read it. */
    private String evidenceFilePath;
    private String evidenceFileName;

    /** Every file frozen with this item, in upload order. Empty for cycles snapshotted before
     * multi-file evidence existed -- those genuinely only ever recorded one. */
    private List<EvidenceFileDto> evidenceFiles = List.of();
    private String lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    /** With the item's frozen evidence attached. */
    public static DeltaCycleItemDto fromEntity(DeltaCycleItem item,
                                                List<com.cloudfuze.deltatracker.entity.DeltaCycleItemEvidence> frozen) {
        DeltaCycleItemDto dto = fromEntity(item);
        dto.setEvidenceFiles(frozen == null ? List.of() : frozen.stream()
                .map(f -> {
                    EvidenceFileDto file = new EvidenceFileDto();
                    file.setId(f.getId());
                    file.setFilePath(f.getFilePath());
                    file.setFileName(f.getFileName());
                    return file;
                })
                .toList());
        return dto;
    }

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
