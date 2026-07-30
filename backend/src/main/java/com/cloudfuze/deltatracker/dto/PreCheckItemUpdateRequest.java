package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PreCheckItemUpdateRequest {

    // status is written unconditionally into a NOT NULL column, so a null here used to fail at insert
    // (DataIntegrityViolation) rather than at bind. @NotNull turns that into a clean 400. The frontend
    // always sends the current status (defaulting to NOT_STARTED), so this changes no valid flow.
    @NotNull(message = "A status is required")
    private ItemStatus status;

    // Sizes match the entity columns: notes VARCHAR(2000), evidence path/name/actor default VARCHAR(255).
    @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
    private String notes;

    @Size(max = 255, message = "Evidence file path must be 255 characters or fewer")
    private String evidenceFilePath;

    @Size(max = 255, message = "Evidence file name must be 255 characters or fewer")
    private String evidenceFileName;

    @Size(max = 255, message = "Updated-by must be 255 characters or fewer")
    private String updatedBy;
}
