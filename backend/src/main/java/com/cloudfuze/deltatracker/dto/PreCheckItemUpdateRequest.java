package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

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

    /**
     * Every evidence file on this item, replacing whatever it had.
     *
     * <p>null means "the caller is not managing the list", in which case the two single-file fields
     * above are used exactly as before -- that is what keeps an older client working. An empty list
     * is different and meaningful: it clears the evidence.
     *
     * <p>Replace rather than append, for the same reason the team checkboxes replace: the panel
     * shows the whole list, so what it sends back IS the whole list, and removing a file then needs
     * no second endpoint.
     */
    @Valid
    @Size(max = 20, message = "An item can carry at most 20 evidence files")
    private List<EvidenceFileRequest> evidenceFiles;

    /** A file the browser has already uploaded via POST /api/uploads. */
    @Getter
    @Setter
    public static class EvidenceFileRequest {

        @NotBlank(message = "An evidence file path is required")
        @Size(max = 255, message = "Evidence file path must be 255 characters or fewer")
        private String filePath;

        @Size(max = 255, message = "Evidence file name must be 255 characters or fewer")
        private String fileName;
    }

    @Size(max = 255, message = "Updated-by must be 255 characters or fewer")
    private String updatedBy;
}
