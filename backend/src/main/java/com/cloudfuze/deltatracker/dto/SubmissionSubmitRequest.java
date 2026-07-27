package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmissionSubmitRequest {

    @NotBlank
    private String submittedBy;
}
