package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Creating a Server directly under a project (the "Server URL" add flow), without going through
// a CSV import.
@Getter
@Setter
public class CreateServerRequest {

    // Max matches the servers.name column (default VARCHAR(255)).
    @NotBlank
    @Size(max = 255, message = "Server URL must be 255 characters or fewer")
    private String name;
}
