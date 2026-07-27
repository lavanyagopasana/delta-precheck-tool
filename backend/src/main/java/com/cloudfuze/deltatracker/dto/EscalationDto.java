package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.Escalation;
import com.cloudfuze.deltatracker.entity.EscalationPriority;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class EscalationDto {

    private Long id;
    private Long serverId;
    private String serverName;
    private String ticketNumber;
    private String description;
    private String reason;
    private String createdBy;
    private LocalDateTime createdAt;
    private EscalationStatus status;
    private EscalationPriority priority;
    private String resolutionNotes;
    private String evidenceFilePath;
    private String evidenceFileName;

    public static EscalationDto fromEntity(Escalation escalation) {
        EscalationDto dto = new EscalationDto();
        dto.setId(escalation.getId());
        dto.setServerId(escalation.getServer().getId());
        dto.setServerName(escalation.getServer().getName());
        dto.setTicketNumber(escalation.getTicketNumber());
        dto.setDescription(escalation.getDescription());
        dto.setReason(escalation.getReason());
        dto.setCreatedBy(escalation.getCreatedBy());
        dto.setCreatedAt(escalation.getCreatedAt());
        dto.setStatus(escalation.getStatus());
        dto.setPriority(escalation.getPriority());
        dto.setResolutionNotes(escalation.getResolutionNotes());
        dto.setEvidenceFilePath(escalation.getEvidenceFilePath());
        dto.setEvidenceFileName(escalation.getEvidenceFileName());
        return dto;
    }
}
