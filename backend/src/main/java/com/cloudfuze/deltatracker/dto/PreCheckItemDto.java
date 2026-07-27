package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class PreCheckItemDto {

    private Long id;
    private Long serverId;
    private String itemName;
    private ItemStatus status;
    private String notes;
    private String evidenceFilePath;
    private String evidenceFileName;
    private String lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    public static PreCheckItemDto fromEntity(PreCheckItem item) {
        PreCheckItemDto dto = new PreCheckItemDto();
        dto.setId(item.getId());
        dto.setServerId(item.getServerId());
        dto.setItemName(item.getItemName());
        dto.setStatus(item.getStatus());
        dto.setNotes(item.getNotes());
        dto.setEvidenceFilePath(item.getEvidenceFilePath());
        dto.setEvidenceFileName(item.getEvidenceFileName());
        dto.setLastModifiedBy(item.getLastModifiedBy());
        dto.setLastModifiedAt(item.getLastModifiedAt());
        return dto;
    }

    // Hides another editor's in-progress work from everyone else until they submit -- keeps only
    // the static item identity/name, none of the actual progress (status, notes, evidence, who).
    public static PreCheckItemDto redacted(PreCheckItem item) {
        PreCheckItemDto dto = new PreCheckItemDto();
        dto.setId(item.getId());
        dto.setServerId(item.getServerId());
        dto.setItemName(item.getItemName());
        dto.setStatus(ItemStatus.NOT_STARTED);
        return dto;
    }
}
