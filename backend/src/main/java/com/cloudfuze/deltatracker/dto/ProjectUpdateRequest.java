package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Editing an existing project's details. migrationManagerName is nullable (blank clears it, only
// allowed when no approval is in progress). createdBy/engineerEmails aren't edited here --
// engineers are managed via ProjectAssignmentRequest.
@Getter
@Setter
public class ProjectUpdateRequest {

    // Was @NotNull -- that let "" through and saved a blank project name (inconsistent with create).
    // @NotBlank closes the API-level gap; the frontend already blocks empty submission.
    @NotBlank
    @Size(max = 255, message = "Project name must be 255 characters or fewer")
    private String name;

    @NotNull
    private ProductType productType;

    private String migrationManagerName;
}
