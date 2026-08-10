package com.cloudfuze.deltatracker.entity;

// Where a DeltaCycle sits in its own (post-approval) life. A cycle row normally only comes into
// existence once the sign-off chain has fully resolved -- DECLINED is the one exception, written
// when the chain is cut short instead (see DeltaCycleService.recordDeclineAndRollOver). There is
// still no "in review" state: that phase is represented by the live PreCheckSubmission/SignOff rows
// instead (see DeltaCycle's javadoc).
public enum DeltaCycleStatus {
    // Chain resolved, Delta initiated, waiting on the engineer to start the actual migration.
    APPROVED,
    // Engineer clicked Start; migration is running.
    RUNNING,
    // Engineer clicked Finish. Terminal for the cycle -- a PRE_DELTA cycle rolls the combination
    // over to a fresh checklist at this point, a FINAL_DELTA cycle ends the combination entirely.
    COMPLETED,
    // Some role declined instead of approving. Terminal for the cycle -- the checklist and
    // sign-off outcomes are frozen exactly as they stood at the moment of the decline, and the
    // combination immediately rolls over to a fresh cycle for the engineers to redo.
    DECLINED
}
