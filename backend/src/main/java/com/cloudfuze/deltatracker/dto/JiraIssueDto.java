package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// Internal transport object between JiraService and TicketService -- never returned directly from a
// controller (TicketDto is the one that crosses the API boundary).
@Getter
@Setter
public class JiraIssueDto {

    private String key;
    private String url;
    private String summary;
    private boolean resolved;
    private String reporterDisplayName;
    private LocalDateTime createdAt;
}
