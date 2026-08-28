package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// One combination (e.g. "Box to OneDrive") within a Server -- the unit Delta readiness is tracked
// at now, instead of Server itself. A Server can have several of these, each with its own pre-check
// checklist, its own three-role sign-off chain, and its own Delta lifecycle. Rows are created
// on-demand the first time a CSV is imported under a new combination name for a server (see
// WorkspacePairService), mirroring how a Server itself is auto-created from a CSV row. Deliberately
// NOT linked to WorkspacePair via a foreign key -- WorkspacePair keeps its existing free-text
// `combination` column (matched case-insensitively), so this table only exists to give
// PreCheckItem/PreCheckSubmission/SignOff something to hang off of.
@Entity
@Table(name = "workspace_combinations", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceCombination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PairStatus status = PairStatus.PENDING;

    @Column(name = "delta_initiated_at")
    private LocalDateTime deltaInitiatedAt;

    @Column(name = "delta_initiated_by")
    private String deltaInitiatedBy;

    // Post-Delta lifecycle, driven by the engineer: after Delta is initiated they Start the actual
    // migration, then mark it Finished. Both are timestamps stamped at click time (null until then).
    // All six delta_* columns above describe the CURRENT cycle only -- they're cleared on every
    // rollover to a new pre-delta. The per-cycle history lives in delta_cycles (see DeltaCycle).
    @Column(name = "delta_started_at")
    private LocalDateTime deltaStartedAt;

    @Column(name = "delta_started_by")
    private String deltaStartedBy;

    @Column(name = "delta_finished_at")
    private LocalDateTime deltaFinishedAt;

    @Column(name = "delta_finished_by")
    private String deltaFinishedBy;

    // Which cycle is being filled out / reviewed / run right now. 1-based, ALWAYS increments on every
    // rollover (decline or finish) -- this is purely an internal bookkeeping/ordering key (the
    // delta_cycles unique constraint is keyed on it), never shown to a user directly. Defaulted at
    // both the Java and column level so rows predating this feature read as cycle 1 rather than 0
    // under ddl-auto=update.
    @Column(name = "current_cycle_number", nullable = false, columnDefinition = "int default 1")
    private int currentCycleNumber = 1;

    // The user-facing "Delta N.M" pair -- separate from currentCycleNumber above on purpose. Major
    // only advances when a Pre-Delta is genuinely approved and FINISHED; minor advances on every
    // decline-triggered redo of the SAME Pre-Delta and resets to 1 whenever major advances. Without
    // this split, a declined-and-redone Pre-Delta 2 read as "Delta 3" on the live pre-check form (and
    // everywhere else this number is shown) the moment it rolled over, indistinguishable from an
    // actually-new Pre-Delta -- see DeltaCycleService.rollOver.
    @Column(name = "current_delta_major", nullable = false, columnDefinition = "int default 1")
    private int currentDeltaMajor = 1;

    @Column(name = "current_delta_minor", nullable = false, columnDefinition = "int default 1")
    private int currentDeltaMinor = 1;

    // The current cycle's declared type, copied from the "Delta Type" checklist item at submit time
    // (PreCheckSubmissionService.submit) and cleared on withdrawal or rollover. Denormalized onto the
    // combination rather than re-read from the item on every request because it has to survive the
    // item's reset to NOT_STARTED, and because it's what actually gets approved -- reading the live
    // item instead would let an admin edit of a submitted form silently change a cycle's nature after
    // an approver had already acted on it.
    @Enumerated(EnumType.STRING)
    @Column(name = "current_delta_type")
    private DeltaType currentDeltaType;

    // Stamped when a FINAL_DELTA cycle is marked finished. Non-null means this combination is done
    // for good: no further pre-check editing or submission, and it now counts toward its server
    // becoming decommission-ready. This -- not deltaFinishedAt -- is the "migration complete" signal,
    // since deltaFinishedAt is also set by every intermediate pre-delta.
    @Column(name = "final_delta_completed_at")
    private LocalDateTime finalDeltaCompletedAt;

    @Column(name = "final_delta_completed_by")
    private String finalDeltaCompletedBy;

    public WorkspaceCombination(Server server, String name) {
        this.server = server;
        this.name = name;
    }

    // True once the FINAL_DELTA cycle has been marked finished -- the combination is locked from here.
    public boolean isFinalDeltaComplete() {
        return finalDeltaCompletedAt != null;
    }

    // "2.1" / "3.2" -- the number a human reads, as opposed to currentCycleNumber's internal bookkeeping.
    public String currentDeltaCycleLabel() {
        return currentDeltaMajor + "." + currentDeltaMinor;
    }
}
