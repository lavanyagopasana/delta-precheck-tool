package com.cloudfuze.deltatracker.entity;

// What a single Delta cycle actually is. Deliberately a separate enum from ItemStatus even though
// ItemStatus also carries PRE_DELTA/FINAL_DELTA values: those are the *checklist item's* status (the
// engineer's answer to the "Delta Type" question while filling the form, which resets to
// NOT_STARTED on every rollover), whereas this is the *cycle's* settled nature, locked in at submit
// time and never reset. Mapping between the two happens once, in
// PreCheckSubmissionService.resolveDeltaType.
public enum DeltaType {
    PRE_DELTA,
    FINAL_DELTA;

    // Cycle label shown everywhere in the UI -- "Pre-Delta 2.1" names the pre-delta and which attempt
    // at it this is (the ".1"/".2" advances on a decline-triggered redo of the SAME pre-delta, the
    // leading number only on a genuine finish -- see WorkspaceCombination.currentDeltaMajor/Minor).
    // Final Delta is unique per combination so a number would be noise.
    //
    // No "#": inside a small pill next to labels like "10 pairs" the hash read as clutter, and it made
    // the badge look like it was quoting an issue number rather than naming a cycle. The bare number
    // reads the same and sits better.
    public String label(String cycleLabel) {
        return this == PRE_DELTA ? "Pre-Delta " + cycleLabel : "Final Delta";
    }

    /**
     * The cycle label with its phase — "Pre-Delta 1 - started", "Final Delta - completed".
     *
     * <p>The bare label was ambiguous: "Pre-Delta 1" read the same whether its pre-check was still
     * being approved, its migration was running, or it had already finished. Someone scanning a
     * project's combinations couldn't tell which ones were actually moving.
     *
     * <p>The " - " separator is deliberate rather than a plain space: it splits the cycle's identity
     * from its state, so "Pre-Delta 1 - in progress" doesn't read as one run-on phrase. Applies to
     * Final Delta identically.
     *
     * <p>READY is "ready to start" — approvals are done and the engineer can click Start. FINISHED is
     * "done" — this cycle's migration was marked finished via the Finish button.
     * completeness, though callers render no chip at all in that state (there is no settled type yet).
     */
    public String labelWithPhase(String cycleLabel, DeltaPhase phase) {
        String base = label(cycleLabel);
        return switch (phase) {
            case IN_APPROVAL -> base + " - in progress";
            case READY -> base + " - ready to start";
            case STARTED -> base + " - started";
            case FINISHED -> base + " - done";
            case COMPLETE -> base + " - completed";
            case AWAITING_PRECHECK -> base;
        };
    }
}
