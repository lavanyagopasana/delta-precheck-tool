package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaPhase;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// The per-combination equivalent of ServerReadinessDto -- one combination's own pre-check,
// sign-off, and Delta lifecycle. Tickets are combination-scoped (Ticket -> WorkspaceCombination),
// not server-level -- see TicketService.countOpenForCombination/countTotalForCombination.
@Getter
@Setter
public class CombinationReadinessDto {

    private Long combinationId;
    private String combinationName;
    private Long serverId;
    private String serverName;
    // The server's product type. Drives which status options each checklist item offers -- Message's
    // "Delta Message Sync" is enabled/not enabled, its OneTime Migration adds "partially completed".
    // Sent with the readiness payload so PreCheckPanel doesn't need a second request to render a
    // dropdown correctly.
    private ProductType productType;
    private Long projectId;
    private String projectName;
    private String migrationManagerName;

    private PairStatus status;
    private int totalPairs;
    private long openEscalationCount;
    // All tickets logged against this combination, open or resolved. The detail view shows this
    // total rather than openEscalationCount alone, so a resolved ticket doesn't just disappear from
    // the number the moment it's closed -- openEscalationCount is kept too, since it still drives
    // the red-highlight ("needs attention") styling.
    private long totalTicketCount;
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
    // "Pre-Delta 2 - started" / "Final Delta - done" for the current cycle, or null when
    // currentDeltaType is. Resolved server-side (DeltaType.labelWithPhase) so the rule lives in one place.
    private String currentDeltaLabel;
    // Where the current cycle has got to — drives badge colour on the detail page.
    private DeltaPhase deltaPhase;
    // How many cycles have been marked finished (Finish clicked) — not cycles still awaiting start.
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
    // What the approver objected to. The pre-check view is where the engineer is told to correct and
    // resubmit, so it is the one place that has to carry the reason -- telling someone to fix it
    // without saying what was wrong is the whole problem the reason was added to solve. Already sent
    // on SignOffApprovalDto for the Approvals table; this is the same value for the detail view.
    private String declineReason;

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
