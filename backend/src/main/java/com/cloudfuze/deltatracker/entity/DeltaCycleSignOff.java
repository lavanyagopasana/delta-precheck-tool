package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A frozen copy of one SignOff row as it stood when its cycle's chain resolved -- who approved each
// of the three roles and when. Needed because the live sign_offs rows are deleted on rollover (the
// unique(combination_id, role) constraint only allows one chain per combination at a time), so
// without this the "who approved Pre-Delta 1" answer would be lost the moment Pre-Delta 2 starts.
// SKIPPED here means the Dev Lead decided QA Lead approval wasn't required for that cycle.
@Entity
@Table(name = "delta_cycle_signoffs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cycle_id", "role"}))
@Getter
@Setter
@NoArgsConstructor
public class DeltaCycleSignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private DeltaCycle cycle;

    @Column(name = "cycle_id", insertable = false, updatable = false)
    private Long cycleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffStatus status;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // Only meaningful on the Dev Lead row -- whether they required QA Lead approval for this cycle.
    @Column(name = "qa_required")
    private Boolean qaRequired;

    // Frozen copy of SignOff.declineReason -- only ever set on the row whose decline ended this
    // cycle, since the live row (and its reason) is gone once the rollover that follows deletes it.
    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    public DeltaCycleSignOff(DeltaCycle cycle, SignOff source) {
        this.cycle = cycle;
        this.role = source.getRole();
        this.status = source.getStatus();
        this.approvedBy = source.getApprovedBy();
        this.approvedAt = source.getApprovedAt();
        this.qaRequired = source.getQaRequired();
        this.declineReason = source.getDeclineReason();
    }
}
