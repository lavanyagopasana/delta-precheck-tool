package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * One project as read from the PMO tool's {@code GET /api/external/projects} feed, normalised to the
 * fields this tracker can act on or usefully display. PMO reports more than this per project
 * (planType, plannedStart/End, actualStart/End, delayStatus, delayDays, phaseCompletionPct,
 * accountManager, updatedAt); those are deliberately dropped rather than mirrored, because this tool
 * owns pre-migration checklist compliance and a stale copy of another system's schedule numbers
 * would invite people to trust figures nothing here maintains.
 */
@Getter
@Setter
public class PmoProjectDto {

    /** PMO's UUID primary key. Stored on Project.externalId; a record without one is skipped. */
    private String externalId;

    /** PMO's project name, untrimmed as received. Not unique in the feed -- see PmoSyncService.assignNames. */
    private String name;

    private String customerName;

    /**
     * PMO's project manager as a DISPLAY NAME ("Harika"), never an email -- all 11 distinct values in
     * the live feed are bare names. Deliberately not written to Project.migrationManagerName, which is
     * compared as an email everywhere in this app.
     */
    private String managerName;

    /** ACTIVE / ON_HOLD / COMPLETED / CANCELLED. Drives which records the sync imports at all. */
    private String status;

    /** PMO's own phase: KICKOFF, PILOT_MIGRATION, ONETIME_MIGRATION, DELTA, FINAL_VALIDATION, CLOSURE, COMPLETED. */
    private String phase;

    /** Comma-separated "Source - Destination" pairs, e.g. "Gmail - Gmail". */
    private String migrationTypes;
}
