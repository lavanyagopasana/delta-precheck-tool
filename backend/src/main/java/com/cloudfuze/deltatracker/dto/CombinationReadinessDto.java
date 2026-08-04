package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// The per-combination equivalent of ServerReadinessDto -- one combination's own pre-check,
// sign-off, and Delta lifecycle. Escalation count is still server-level (tickets aren't split per
// combination -- see .claude/memory or the architecture skill for that scope decision).
@Getter
@Setter
public class CombinationReadinessDto {

    private Long combinationId;
    private String combinationName;
    private Long serverId;
    private String serverName;
    private Long projectId;
    private String projectName;
    private String migrationManagerName;

    private PairStatus status;
    private int totalPairs;
    private long openEscalationCount;
    private String readinessStatus;
    // One of READY / NOT_SUBMITTED / IN_PROGRESS -- READY means the pre-check is submitted and all
    // three roles have approved. readinessDetail explains the other two: "Pre-check isn't submitted
    // yet", or "<Role> not approved yet" naming whichever role is next. Mirrors
    // ProjectService.applyReadinessStage's per-combination logic, just for one combination instead
    // of aggregating across a server's several.
    private String readinessStage;
    private String readinessDetail;

    private LocalDateTime deltaInitiatedAt;
    private String deltaInitiatedBy;
    private LocalDateTime deltaStartedAt;
    private String deltaStartedBy;
    private LocalDateTime deltaFinishedAt;
    private String deltaFinishedBy;

    // NOT_STARTED / DRAFT / SUBMITTED -- independent of the approval chain. Used by the frontend to
    // color/label the pre-check button (e.g. "Start" vs "Continue" vs "View").
    private SubmissionStatus submissionStatus;

    private List<WorkspacePairDto> pairs;
}
