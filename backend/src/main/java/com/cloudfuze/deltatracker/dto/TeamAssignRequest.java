package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeamAssignRequest {

    @NotBlank(message = "An email is required.")
    private String email;

    // Null is meaningful: it takes the person off every team rather than being a missing value.
    private Long teamId;
}
