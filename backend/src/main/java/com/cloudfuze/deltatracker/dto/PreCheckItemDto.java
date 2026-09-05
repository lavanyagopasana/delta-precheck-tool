package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class PreCheckItemDto {

    private Long id;
    private Long combinationId;
    private String itemName;
    private ItemStatus status;
    private String notes;
    /**
     * The FIRST evidence file, unchanged in meaning from when an item could only have one -- the
     * submit precondition, the history snapshot and the attachment preview all still read it, which
     * is why mirroring it costs nothing and changing it would have cost a lot.
     */
    private String evidenceFilePath;
    private String evidenceFileName;

    /** Every evidence file on the item, first uploaded first. Empty when there is none. */
    private List<EvidenceFileDto> evidenceFiles = List.of();
    private String lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    /**
     * Without the item's evidence rows -- the single-file fields only.
     *
     * <p>Kept for the callers that map an item outside a request that has already loaded evidence
     * (the delete/reset paths, and anything that only needs status). evidenceFiles comes back empty
     * there rather than wrong: an empty list is honest about what was loaded, whereas guessing from
     * evidenceFilePath would invent a list of one that may be missing several.
     */
    public static PreCheckItemDto fromEntity(PreCheckItem item) {
        PreCheckItemDto dto = new PreCheckItemDto();
        dto.setId(item.getId());
        dto.setCombinationId(item.getCombinationId());
        dto.setItemName(item.getItemName());
        dto.setStatus(item.getStatus());
        dto.setNotes(item.getNotes());
        dto.setEvidenceFilePath(item.getEvidenceFilePath());
        dto.setEvidenceFileName(item.getEvidenceFileName());
        dto.setLastModifiedBy(item.getLastModifiedBy());
        dto.setLastModifiedAt(item.getLastModifiedAt());
        return dto;
    }

    /** With the item's evidence files attached. */
    public static PreCheckItemDto fromEntity(PreCheckItem item,
                                              List<com.cloudfuze.deltatracker.entity.PreCheckItemEvidence> evidence) {
        PreCheckItemDto dto = fromEntity(item);
        dto.setEvidenceFiles(evidence == null
                ? List.of()
                : evidence.stream().map(EvidenceFileDto::fromEntity).toList());
        return dto;
    }
}
