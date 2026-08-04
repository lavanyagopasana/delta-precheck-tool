package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    // Rollup of this server's WorkspaceCombinations' own statuses (WorkspaceCombinationService
    // recomputes this on every change) -- DELTA_READY only once every combination is. Delta
    // initiated/started/finished timestamps live on WorkspaceCombination now, not here: each
    // combination has its own independent Delta lifecycle, so one server-wide timestamp can't
    // represent them once a server has more than one combination.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PairStatus status = PairStatus.PENDING;

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
