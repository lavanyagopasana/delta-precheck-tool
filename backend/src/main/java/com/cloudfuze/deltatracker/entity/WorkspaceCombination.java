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
    @Column(name = "delta_started_at")
    private LocalDateTime deltaStartedAt;

    @Column(name = "delta_started_by")
    private String deltaStartedBy;

    @Column(name = "delta_finished_at")
    private LocalDateTime deltaFinishedAt;

    @Column(name = "delta_finished_by")
    private String deltaFinishedBy;

    public WorkspaceCombination(Server server, String name) {
        this.server = server;
        this.name = name;
    }
}
