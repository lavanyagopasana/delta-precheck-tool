package com.cloudfuze.deltatracker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

// The "project" object inside a PMO delta-phase webhook payload. Field names match PMO's JSON
// exactly (id, projectManager) rather than this app's own vocabulary (externalId, managerName) --
// PmoWebhookService is what translates between the two, same as PmoProjectDto does for the polled
// feed. Only the fields this app actually uses are mapped; @JsonIgnoreProperties tolerates the rest
// (accountManager, phaseCompletionPct, planType, the plannedStart/actualStart dates, delayStatus,
// delayDays, updatedAt) without failing deserialization.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PmoWebhookProjectDto {

    private String id;
    private String name;
    private String customerName;
    private String projectManager;
    private String status;
    private String phase;
    private String migrationTypes;
}
