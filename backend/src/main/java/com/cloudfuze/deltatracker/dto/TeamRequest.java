package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeamRequest {

    @NotBlank(message = "A team needs a name.")
    private String name;
}
