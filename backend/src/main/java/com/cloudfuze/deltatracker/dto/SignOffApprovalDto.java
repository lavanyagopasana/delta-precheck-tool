package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.ProductType;
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
    private ProductType productType;
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

    // Which Delta cycle this approval request belongs to, so approvers can see the stakes before
    // acting: a Final Delta is irreversible and makes the server decommissionable, whereas a
    // Pre-Delta will come round again. deltaType is null only if the request predates this field.
    private int cycleNumber;
    private DeltaType deltaType;
    // "Pre-Delta 2" / "Final Delta" -- resolved server-side via DeltaType.label so the numbering
    // rule isn't reimplemented per consumer.
    private String deltaLabel;

    // Why the most recent decline happened, and which role gave it. Surfaced next to the status so the
    // person the chain bounced back to sees the objection without opening anything.
    private String declineReason;
    private String declinedByRoleLabel;
    private String declinedBy;
}
