package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "precheck_submissions", uniqueConstraints = @UniqueConstraint(columnNames = {"combination_id"}))
@Getter
@Setter
@NoArgsConstructor
public class PreCheckSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combination_id", nullable = false)
    private WorkspaceCombination combination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.NOT_STARTED;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "started_by_email")
    private String startedByEmail;

    public PreCheckSubmission(WorkspaceCombination combination) {
        this.combination = combination;
        this.status = SubmissionStatus.NOT_STARTED;
    }
}
