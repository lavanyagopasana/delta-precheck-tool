package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SignOffApprovalDto {

    private Long id;
    private Long combinationId;
    private String combinationName;
    private Long serverId;
    private String serverName;
    private String readinessStatus;
    private int totalPairs;
    private long openEscalationCount;
    private Long projectId;
    private String projectName;
    private SignOffRole role;
    // Display label only -- "the project's manager" for MIGRATION_LEAD, "Any Dev Lead"/"Any QA Lead"
    // for the pool roles. Not used for eligibility; canAct is computed server-side per caller.
    private String assignedName;
    private SignOffStatus status;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String overallStatus;
    private String currentStatus;
    private boolean turnReady;
    private boolean canAct;
}
