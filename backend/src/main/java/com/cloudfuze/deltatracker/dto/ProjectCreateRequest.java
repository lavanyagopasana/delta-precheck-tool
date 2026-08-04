package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectCreateRequest {

    // Max matches the projects.name column (default VARCHAR(255)); it is also uniquely constrained.
    @NotBlank
    @Size(max = 255, message = "Project name must be 255 characters or fewer")
    private String name;

    // Not asked at creation time -- set later via the project's Edit form. The entity column is
    // already nullable, so a project can genuinely have no product type until then.
    private ProductType productType;

    private String createdBy;

    // Only used when the creator isn't a Migration Manager themselves -- a Migration Manager
    // creating a project is automatically that project's manager.
    private String migrationManagerName;
}
