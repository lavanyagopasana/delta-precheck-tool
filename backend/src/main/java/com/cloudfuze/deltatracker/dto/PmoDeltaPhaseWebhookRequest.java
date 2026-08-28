package com.cloudfuze.deltatracker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

// Payload PMO Tracker POSTs to /api/webhooks/pmo/delta-phase the moment a project moves into Delta
// phase. "recentDeltaProjects" (every other project that entered Delta in the last 7 days) is
// deliberately not mapped here -- PMO's own spec for this webhook says it is context only, not a
// second event to process, so @JsonIgnoreProperties lets it pass through unread rather than this
// class carrying a field nothing ever looks at.
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PmoDeltaPhaseWebhookRequest {

    private String event;
    private PmoWebhookProjectDto project;
}
