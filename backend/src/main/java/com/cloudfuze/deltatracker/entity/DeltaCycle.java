package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One completed (or in-flight-post-approval) Delta iteration for a combination: an immutable record
 * of what was approved and when. A combination runs any number of PRE_DELTA cycles before exactly
 * one FINAL_DELTA cycle, after which it's finished and its server can be decommissioned.
 *
 * <p><b>Why this is a snapshot rather than a cycle_number column on the live tables.</b> The obvious
 * alternative was to add {@code cycle_number} to precheck_items/precheck_submissions/sign_offs and
 * keep every cycle's rows side by side. Two things ruled that out. First, it needs
 * {@code unique(combination_id)} and {@code unique(combination_id, role)} dropped and replaced --
 * and Hibernate's {@code ddl-auto=update} adds constraints but never drops them, so every existing
 * database would reject the second cycle's insert with no migration tool available to fix it.
 * Second, roughly a dozen {@code findByCombinationId} call sites across six services (plus the
 * dashboard and project rollups) implicitly mean "the current cycle"; making them all cycle-aware
 * and missing even one would silently multiply every count by the number of cycles run. Keeping the
 * live tables single-cycle means all of that stays correct without being touched.
 *
 * <p>So the live rows always hold exactly the current cycle, and this table holds the history. The
 * row is written at one single point -- {@link com.cloudfuze.deltatracker.service.DeltaCycleService}
 * {@code .recordApproval}, called when the sign-off chain fully resolves -- together with a frozen
 * copy of every checklist item ({@link DeltaCycleItem}) and every sign-off outcome
 * ({@link DeltaCycleSignOff}). Nothing here is ever created for a submission that gets withdrawn
 * before approval, which is why there's no IN_REVIEW state: an un-approved cycle simply has no row
 * yet, and the live PreCheckSubmission/SignOff rows represent that phase.
 *
 * <p>Evidence files are safe across a rollover: reset clears the live item's file pointer but never
 * deletes anything under uploads/, so a snapshot's evidenceFilePath keeps resolving forever.
 */
@Entity
@Table(name = "delta_cycles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"combination_id", "cycle_number"}))
@Getter
@Setter
@NoArgsConstructor
public class DeltaCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combination_id", nullable = false)
    private WorkspaceCombination combination;

    @Column(name = "combination_id", insertable = false, updatable = false)
    private Long combinationId;

    // 1-based, per combination. Mirrors WorkspaceCombination.currentCycleNumber at the moment the
    // chain resolved.
    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "delta_type", nullable = false)
    private DeltaType deltaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeltaCycleStatus status = DeltaCycleStatus.APPROVED;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "delta_initiated_at")
    private LocalDateTime deltaInitiatedAt;

    @Column(name = "delta_initiated_by")
    private String deltaInitiatedBy;

    @Column(name = "delta_started_at")
    private LocalDateTime deltaStartedAt;

    @Column(name = "delta_started_by")
    private String deltaStartedBy;

    @Column(name = "delta_finished_at")
    private LocalDateTime deltaFinishedAt;

    @Column(name = "delta_finished_by")
    private String deltaFinishedBy;

    // Only set on a DECLINED cycle -- which role declined, who they were, and why. Mirrors the same
    // three facts SignOff.declineReason captures live, frozen here since the live row is deleted by
    // the rollover that immediately follows a decline.
    @Enumerated(EnumType.STRING)
    @Column(name = "declined_by_role")
    private SignOffRole declinedByRole;

    @Column(name = "declined_by")
    private String declinedBy;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    public DeltaCycle(WorkspaceCombination combination, int cycleNumber, DeltaType deltaType) {
        this.combination = combination;
        this.cycleNumber = cycleNumber;
        this.deltaType = deltaType;
        this.status = DeltaCycleStatus.APPROVED;
    }
}
