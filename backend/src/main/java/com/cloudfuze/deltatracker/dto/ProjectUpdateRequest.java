package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

// Editing an existing project's details. migrationManagerName is nullable (blank clears it, only
// allowed when no approval is in progress). createdBy/engineerEmails aren't edited here --
// engineers are managed via ProjectAssignmentRequest.
@Getter
@Setter
public class ProjectUpdateRequest {

    @NotNull
    private String name;

    @NotNull
    private ProductType productType;

    private String migrationManagerName;
}
