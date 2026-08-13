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
// The projects list and every project detail view fetch a project's servers, and duplicate-name
// checks hit findByProjectIdAndNameIgnoreCase. project_id had no index of any kind.
@Table(name = "servers", indexes = {
        @Index(name = "idx_server_project", columnList = "project_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // One product type per Server URL, shared by every combination under it. Nullable -- a server
    // can exist with no product type set yet (chosen at creation time, editable afterward).
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private ProductType productType;

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

    // Decommissioning is per-server (not per-project): a server becomes eligible once every one of
    // its combinations has completed its FINAL_DELTA, and an ADMIN then confirms it explicitly. Kept
    // as a stamped timestamp rather than a derived flag because "eligible to decommission" and
    // "actually decommissioned" are different facts -- see ServerService.decommission.
    @Column(name = "decommissioned_at")
    private LocalDateTime decommissionedAt;

    @Column(name = "decommissioned_by")
    private String decommissionedBy;

    public Server(String name) {
        this.name = name;
    }

    public boolean isDecommissioned() {
        return decommissionedAt != null;
    }
}
