package com.cloudfuze.deltatracker.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase resolution and the labels built from it.
 *
 * <p>The rule that matters: the timestamps accumulate rather than replace each other, so a finished
 * cycle still carries deltaStartedAt and deltaInitiatedAt. DeltaPhase.of therefore has to check
 * most-advanced-first -- reversing the order would report every finished combination as merely
 * started, which is exactly the ambiguity the phase label exists to remove.
 */
class DeltaPhaseTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 8, 7, 12, 0);

    private WorkspaceCombination combination() {
        Server server = new Server("SRV-1");
        server.setId(1L);
        WorkspaceCombination c = new WorkspaceCombination(server, "Google to OneDrive");
        c.setId(1L);
        return c;
    }

    /** Builds a pre-delta combination wound forward to the given phase. */
    private WorkspaceCombination preDeltaAt(DeltaPhase target) {
        WorkspaceCombination c = combination();
        if (target == DeltaPhase.AWAITING_PRECHECK) {
            return c;
        }
        c.setCurrentDeltaType(DeltaType.PRE_DELTA);
        if (target == DeltaPhase.IN_APPROVAL) return c;
        c.setDeltaInitiatedAt(T);
        if (target == DeltaPhase.READY) return c;
        c.setDeltaStartedAt(T);
        if (target == DeltaPhase.STARTED) return c;
        c.setDeltaFinishedAt(T);
        return c;
    }

    @Test
    void anUnsubmittedPreCheckIsAwaitingPreCheck() {
        assertThat(DeltaPhase.of(combination())).isEqualTo(DeltaPhase.AWAITING_PRECHECK);
    }

    @Test
    void submittedButNotYetApprovedIsInApproval() {
        assertThat(DeltaPhase.of(preDeltaAt(DeltaPhase.IN_APPROVAL))).isEqualTo(DeltaPhase.IN_APPROVAL);
    }

    @Test
    void approvedButNotStartedIsReady() {
        assertThat(DeltaPhase.of(preDeltaAt(DeltaPhase.READY))).isEqualTo(DeltaPhase.READY);
    }

    @Test
    void startedIsStarted() {
        assertThat(DeltaPhase.of(preDeltaAt(DeltaPhase.STARTED))).isEqualTo(DeltaPhase.STARTED);
    }

    @Test
    void aFinishedCycleReportsFinishedNotStarted() {
        // The regression guard: deltaStartedAt is still set on a finished cycle.
        WorkspaceCombination c = preDeltaAt(DeltaPhase.FINISHED);
        assertThat(c.getDeltaStartedAt()).isNotNull();
        assertThat(DeltaPhase.of(c)).isEqualTo(DeltaPhase.FINISHED);
    }

    @Test
    void aCompletedFinalDeltaOutranksEveryOtherTimestamp() {
        WorkspaceCombination c = preDeltaAt(DeltaPhase.FINISHED);
        c.setCurrentDeltaType(DeltaType.FINAL_DELTA);
        c.setFinalDeltaCompletedAt(T);
        assertThat(DeltaPhase.of(c)).isEqualTo(DeltaPhase.COMPLETE);
    }

    @Test
    void preDeltaLabelsCarryTheCycleNumberAndPhase() {
        assertThat(DeltaType.PRE_DELTA.labelWithPhase(1, DeltaPhase.IN_APPROVAL)).isEqualTo("Pre-Delta 1 in progress");
        assertThat(DeltaType.PRE_DELTA.labelWithPhase(1, DeltaPhase.READY)).isEqualTo("Pre-Delta 1 approved");
        assertThat(DeltaType.PRE_DELTA.labelWithPhase(1, DeltaPhase.STARTED)).isEqualTo("Pre-Delta 1 started");
        assertThat(DeltaType.PRE_DELTA.labelWithPhase(2, DeltaPhase.FINISHED)).isEqualTo("Pre-Delta 2 finished");
    }

    @Test
    void finalDeltaIsNeverNumbered() {
        // There is only one Final Delta per combination, so a number would be noise.
        assertThat(DeltaType.FINAL_DELTA.labelWithPhase(3, DeltaPhase.COMPLETE)).isEqualTo("Final Delta completed");
        assertThat(DeltaType.FINAL_DELTA.labelWithPhase(3, DeltaPhase.STARTED)).isEqualTo("Final Delta started");
    }
}
