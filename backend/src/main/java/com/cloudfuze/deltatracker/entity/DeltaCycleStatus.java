package com.cloudfuze.deltatracker.entity;

// Where a DeltaCycle sits in its own (post-approval) life. A cycle row only comes into existence
// once the sign-off chain has fully resolved, so there is no "in review" state here -- that phase is
// represented by the live PreCheckSubmission/SignOff rows instead (see DeltaCycle's javadoc).
public enum DeltaCycleStatus {
    // Chain resolved, Delta initiated, waiting on the engineer to start the actual migration.
    APPROVED,
    // Engineer clicked Start; migration is running.
    RUNNING,
    // Engineer clicked Finish. Terminal for the cycle -- a PRE_DELTA cycle rolls the combination
    // over to a fresh checklist at this point, a FINAL_DELTA cycle ends the combination entirely.
    COMPLETED
}
