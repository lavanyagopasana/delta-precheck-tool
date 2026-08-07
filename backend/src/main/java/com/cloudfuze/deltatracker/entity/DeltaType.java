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

    // Cycle label shown everywhere in the UI -- "Pre-Delta 2" numbers the pre-deltas because there
    // can be many, while Final Delta is unique per combination so a number would be noise.
    //
    // No "#": inside a small pill next to labels like "10 pairs" the hash read as clutter, and it made
    // the badge look like it was quoting an issue number rather than naming a cycle. The bare number
    // reads the same and sits better.
    public String label(int cycleNumber) {
        return this == PRE_DELTA ? "Pre-Delta " + cycleNumber : "Final Delta";
    }
}
