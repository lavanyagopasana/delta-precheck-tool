package com.cloudfuze.deltatracker.entity;

/**
 * Where a combination's current Delta cycle has got to.
 *
 * <p>Derived, never stored — {@link #of} reads it off the combination's timestamps, which are the
 * source of truth. It exists because the label shown next to a combination has to distinguish states
 * the cycle number alone can't: "Pre-Delta 1" is true while the pre-check is still being approved,
 * while the migration is running, and after it has finished, which told the reader nothing about
 * whether anything was actually happening.
 *
 * <p>Resolved server-side so the ordering rule lives with the entity that owns those timestamps
 * rather than being re-derived by each caller.
 */
public enum DeltaPhase {
    /** Pre-check not submitted yet, so nothing has settled this cycle's type. */
    AWAITING_PRECHECK,
    /** Submitted; the Migration Manager → Dev Lead → QA Lead chain is still running. */
    IN_APPROVAL,
    /** Chain cleared and Delta initiated, but the engineer hasn't started the migration. */
    READY,
    /** Migration running. */
    STARTED,
    /** This cycle's migration finished. For a pre-delta, another cycle follows. */
    FINISHED,
    /** The Final Delta is done — the combination is closed for good. */
    COMPLETE;

    /**
     * Checked most-advanced-first: the timestamps accumulate rather than replace each other, so a
     * finished cycle still carries deltaStartedAt and deltaInitiatedAt. Reversing this order would
     * report every finished combination as merely started.
     */
    public static DeltaPhase of(WorkspaceCombination combination) {
        if (combination.isFinalDeltaComplete()) {
            return COMPLETE;
        }
        if (combination.getDeltaFinishedAt() != null) {
            return FINISHED;
        }
        if (combination.getDeltaStartedAt() != null) {
            return STARTED;
        }
        if (combination.getDeltaInitiatedAt() != null) {
            return READY;
        }
        // currentDeltaType is only pinned at submit time, so its absence means the pre-check hasn't
        // been submitted -- distinct from "submitted and waiting on an approver".
        return combination.getCurrentDeltaType() == null ? AWAITING_PRECHECK : IN_APPROVAL;
    }
}
