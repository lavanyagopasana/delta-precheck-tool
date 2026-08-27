package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Setting (or clearing) which Metabase database holds a project's migration data.
//
// Deliberately its own request rather than a field on ProjectUpdateRequest: that endpoint's
// permission check is canDelete, which allows non-admins to edit ONLY a project with no servers yet.
// This field is the opposite case -- it matters precisely once servers exist and there is migration
// data to look at -- so the engineers and manager working the project have to be able to set it on a
// project that is already underway.
//
// Blank clears the field (no name set), which is why there is no @NotBlank here.
@Getter
@Setter
public class ProjectMetabaseRequest {

    @Size(max = 255, message = "Metabase database name must be 255 characters or fewer")
    private String metabaseDatabaseName;
}
