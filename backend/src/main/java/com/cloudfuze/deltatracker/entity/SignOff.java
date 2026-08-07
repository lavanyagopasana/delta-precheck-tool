package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sign_offs", uniqueConstraints = @UniqueConstraint(columnNames = {"combination_id", "role"}))
@Getter
@Setter
@NoArgsConstructor
public class SignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic-lock guard: two approvers acting on the same sign-off row concurrently would both
    // read version N; the second flush to commit fails with an optimistic-lock exception (mapped to
    // HTTP 409) instead of silently clobbering the first approval. Defaulted to 0 for rows that
    // predate this column (the ALTER adds it with DEFAULT 0).
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combination_id", nullable = false)
    private WorkspaceCombination combination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffRole role;

    @Column(name = "signed_by", nullable = false)
    private String signedBy;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffStatus status = SignOffStatus.PENDING;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // Only ever set on the Dev Lead row, at the moment the Dev Lead approves -- records whether they
    // said QA Lead approval is required for this combination. Null means the Dev Lead hasn't acted yet.
    // Why this role declined, captured at decline time and shown next to the status so the engineer
    // reading the bounce-back knows what to fix. Kept when the chain moves on rather than cleared: a
    // later approval doesn't make the earlier objection untrue, and the history is the point.
    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    @Column(name = "qa_required")
    private Boolean qaRequired;

    public SignOff(WorkspaceCombination combination, SignOffRole role, String signedBy) {
        this.combination = combination;
        this.role = role;
        this.signedBy = signedBy;
        this.signedAt = LocalDateTime.now();
        this.status = SignOffStatus.PENDING;
    }
}
