package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaPhase;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

// Lightweight entry in ServerReadinessDto.combinations -- just enough for a picker/list UI (e.g.
// labeling the Pre-Check button without a second fetch). Full detail (items, sign-off chain, Delta
// lifecycle) lives at GET /api/combinations/{id}.
@Getter
@Setter
public class CombinationSummaryDto {

    private Long id;
    private String name;
    private int pairCount;
    private PairStatus status;
    private SubmissionStatus submissionStatus;

    // Multi-cycle Delta state, enough for an at-a-glance chip ("Pre-Delta 2", "Final Delta",
    // "Complete") in the project page's server list and Delta Progress table without a per-combination
    // fetch. Full detail still lives at GET /api/combinations/{id}.
    // Internal bookkeeping/ordering key only -- NOT what to show a user; see currentDeltaCycleLabel.
    private int currentCycleNumber;
    // "2.1" / "3.2" -- the fallback shown before currentDeltaLabel exists (pre-check not yet
    // submitted). Major only advances on a genuine Pre-Delta finish, minor on a decline-redo.
    private String currentDeltaCycleLabel;
    private DeltaType currentDeltaType;
    // "Pre-Delta 2" / "Final Delta" for the cycle in flight, or null before the pre-check is
    // submitted (nothing has settled the cycle's type yet).
    private String currentDeltaLabel;
    private long completedCycleCount;
    private boolean finalDeltaComplete;
    // Where the current cycle has got to (see DeltaPhase). currentDeltaLabel above is the bare
    // "Pre-Delta 1"; this is what the project page's chip actually shows.
    private DeltaPhase deltaPhase;

    // Who has claimed the pre-check form, if anyone. A submission can sit at NOT_STARTED and still be
    // claimed -- opening the form stamps startedByEmail before any item is filled in -- so
    // submissionStatus alone can't tell "nobody has touched this" from "someone else holds it".
    // Without this the server page offered a live "Start Pre-Check Form" button for a form that was
    // already locked, and clicking it landed on a read-only notice with nothing to do.
    private String preCheckStartedByEmail;
}
