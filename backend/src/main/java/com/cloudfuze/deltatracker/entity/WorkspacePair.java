package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Indexes matter most on THIS table: it is by far the highest-row-count one (one row per
// source->destination account pair, so thousands per server on a real engagement) and every read
// path filters by server_id. Postgres does not index foreign keys automatically, and no unique
// constraint here covers server_id, so before this every findByServerId/countByServerId was a
// sequential scan of the whole table. Invisible with the 20 rows a test project has; seconds once a
// real customer's pair list lands.
//
// idx_pair_server_combination is composite because WorkspacePairService looks pairs up by server AND
// combination together (findByServerIdAndCombinationIgnoreCase), and a composite index serves the
// server_id-only queries too via its leading column -- so it is deliberately NOT paired with a
// separate single-column server_id index, which would be redundant write cost for no read gain.
@Entity
@Table(name = "workspace_pairs", indexes = {
        @Index(name = "idx_pair_server_combination", columnList = "server_id, combination")
})
@Getter
@Setter
@NoArgsConstructor
public class WorkspacePair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Column(name = "source_email", nullable = false)
    private String sourceEmail;

    @Column(name = "source_path", length = 1000)
    private String sourcePath;

    @Column(name = "destination_email", nullable = false)
    private String destinationEmail;

    @Column(name = "destination_path", length = 1000)
    private String destinationPath;

    @Column(length = 200)
    private String combination;

    public WorkspacePair(Server server, String sourceEmail, String destinationEmail) {
        this.server = server;
        this.sourceEmail = sourceEmail;
        this.destinationEmail = destinationEmail;
    }
}
