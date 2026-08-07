package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.SignOffRole;
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

    // --- Multi-cycle Delta state (see DeltaCycle) ---
    // Which cycle is being filled/reviewed/run right now, 1-based.
    private int currentCycleNumber;
    // The current cycle's declared type, settled at submit time. Null until the pre-check is
    // submitted -- nothing has decided this cycle's nature before then.
    private DeltaType currentDeltaType;
    // "Pre-Delta 2" / "Final Delta" for the current cycle, or null when currentDeltaType is.
    // Resolved server-side (DeltaType.label) so the numbering rule lives in one place.
    private String currentDeltaLabel;
    // How many cycles have been approved and recorded so far -- the "N deltas done" figure.
    private long completedCycleCount;
    // Non-null once the Final Delta has been marked finished: the combination is locked and now
    // counts toward its server becoming decommission-ready.
    private LocalDateTime finalDeltaCompletedAt;
    private String finalDeltaCompletedBy;
    private boolean finalDeltaComplete;

    // --- Decline state ---
    // True when someone in the chain has declined. Matters because withdrawal is admin-only: a decline
    // stalls the pre-check and the engineer can't reopen it themselves, so the UI has to say who
    // declined it and that an admin needs to withdraw it. Without this the form just looks locked.
    private boolean blockedByDecline;
    private SignOffRole declinedByRole;
    private String declinedByRoleLabel;
    private String declinedBy;
    private LocalDateTime declinedAt;

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
