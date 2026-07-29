package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ProjectSummaryDto {

    private Long id;
    private String name;
    private ProductType productType;
    private int serverCount;
    private long totalPairs;
    private long readyServerCount;
    private long notReadyServerCount;
    private long openEscalationCount;
    private List<String> migrationManagers;
    private String migrationManagerName;
    private List<String> engineerEmails;
    private long devApprovalsDone;
    private long devApprovalsPending;
    private long migrationManagerApprovalsDone;
    private long migrationManagerApprovalsPending;
    private LocalDateTime lastPreCheckSubmittedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    // True when the project has servers and every one of them has its Delta finished -- the whole
    // project's migration is done, so it's ready to be decommissioned.
    private boolean decommissionReady;
}
