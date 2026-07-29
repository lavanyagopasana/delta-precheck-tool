package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.EscalationPriority;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

// Editing an existing ticket. serverId/createdBy aren't editable -- a ticket stays with the server
// and reporter it was logged against.
@Getter
@Setter
public class EscalationUpdateRequest {

    @NotBlank
    private String ticketNumber;

    @NotBlank
    private String description;

    @NotBlank
    private String reason;

    @NotNull
    private EscalationStatus status;

    @NotNull
    private EscalationPriority priority;

    private String resolutionNotes;

    private String evidenceFilePath;

    private String evidenceFileName;
}
