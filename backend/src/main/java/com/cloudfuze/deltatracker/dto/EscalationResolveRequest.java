package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EscalationResolveRequest {

    @NotBlank
    private String resolutionNotes;
}
