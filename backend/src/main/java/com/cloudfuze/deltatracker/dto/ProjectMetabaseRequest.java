package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Fixing which Metabase database holds one product type's migration data for a project.
//
// Deliberately its own request rather than a field on ProjectUpdateRequest: that endpoint's permission
// check is canDelete, which allows non-admins to edit ONLY a project with no servers yet. This is the
// opposite case -- it matters precisely once servers exist and there is migration data to look at.
//
// productType is required: the same project can hold one database per product type, so a request
// without it would be ambiguous rather than defaulting to something sensible.
@Getter
@Setter
public class ProjectMetabaseRequest {

    @NotBlank(message = "productType is required (MESSAGE, EMAIL or CONTENT)")
    private String productType;

    // Blank clears this product type's database, which is why there is no @NotBlank here. Clearing is
    // still a change, so it is admin-only once a value has been fixed.
    @Size(max = 255, message = "Metabase database name must be 255 characters or fewer")
    private String databaseName;
}
