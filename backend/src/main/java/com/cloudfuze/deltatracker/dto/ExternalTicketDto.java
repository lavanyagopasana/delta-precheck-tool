package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// Internal transport object between TicketLookupService and TicketService -- never returned directly
// from a controller (TicketDto is the one that crosses the API boundary). Provider-neutral on
// purpose: it is the six-field contract any external tracker has to be able to fill, so swapping the
// tracker behind it (Jira -> Neutara, 2026-08-18) touches only TicketLookupService, not this.
@Getter
@Setter
public class ExternalTicketDto {

    private String key;
    private String url;
    private String summary;
    private boolean resolved;
    private String reporterDisplayName;
    private LocalDateTime createdAt;
}
