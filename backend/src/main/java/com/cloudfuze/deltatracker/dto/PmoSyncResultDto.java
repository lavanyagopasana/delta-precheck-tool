package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of one PMO sync run. Follows the bulk-operation result shape this codebase already uses for
 * CSV imports (AppUserImportResultDto, WorkspacePairImportResultDto): counts plus a per-record error
 * list, because one unusable record must never abort the whole batch.
 */
@Getter
@Setter
public class PmoSyncResultDto {

    /** Records returned by PMO that matched the configured import statuses. */
    private int totalRows;
    private int createdCount;
    private int updatedCount;
    /** Read fine, already matched what we hold -- nothing written. */
    private int unchangedCount;
    /** Returned by PMO but filtered out by pmo.import-statuses (e.g. COMPLETED). */
    private int skippedByStatusCount;

    /** Projects whose Migration Manager was set from PMO's project manager this run. */
    private int managersAssigned;

    /**
     * Distinct PMO project-manager names that could not be matched to a MIGRATION_MANAGER here, so
     * those projects arrived unassigned. Not an error -- three of PMO's eleven managers legitimately
     * have no manager account in this app -- but it needs to be visible, or a project silently sits
     * unworkable. Kept separate from errors for that reason.
     */
    private List<String> unresolvedManagers = new ArrayList<>();

    private List<String> errors = new ArrayList<>();

    public void addError(String message) {
        errors.add(message);
    }
}
