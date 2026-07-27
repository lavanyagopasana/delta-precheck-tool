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
