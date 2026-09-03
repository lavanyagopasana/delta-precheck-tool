package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.PreCheckItemEvidence;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One evidence file on a pre-check item, for reading back.
 *
 * <p>The browser has already uploaded the bytes via POST /api/uploads and holds the path it was
 * given; this is that path plus who attached it and when, so an approver can see the provenance of
 * each file rather than just a list of links.
 */
@Getter
@Setter
@NoArgsConstructor
public class EvidenceFileDto {

    private Long id;
    private String filePath;
    private String fileName;
    private String uploadedBy;
    private LocalDateTime uploadedAt;

    public static EvidenceFileDto fromEntity(PreCheckItemEvidence evidence) {
        EvidenceFileDto dto = new EvidenceFileDto();
        dto.setId(evidence.getId());
        dto.setFilePath(evidence.getFilePath());
        dto.setFileName(evidence.getFileName());
        dto.setUploadedBy(evidence.getUploadedBy());
        dto.setUploadedAt(evidence.getUploadedAt());
        return dto;
    }
}
