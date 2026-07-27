package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.EscalationPriority;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EscalationCreateRequest {

    @NotNull
    private Long serverId;

    @NotBlank
    private String ticketNumber;

    @NotBlank
    private String description;

    @NotBlank
    private String reason;

    @NotBlank
    private String createdBy;

    @NotNull
    private EscalationStatus status;

    @NotNull
    private EscalationPriority priority;

    private String resolutionNotes;

    private String evidenceFilePath;

    private String evidenceFileName;
}
