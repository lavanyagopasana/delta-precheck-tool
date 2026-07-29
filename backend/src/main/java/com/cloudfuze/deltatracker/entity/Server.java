package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "servers")
@Getter
@Setter
@NoArgsConstructor
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_pair_count", nullable = false)
    private int totalPairCount;

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

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @JsonIgnore
    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkspacePair> workspacePairs = new ArrayList<>();

    public Server(String name) {
        this.name = name;
    }
}
