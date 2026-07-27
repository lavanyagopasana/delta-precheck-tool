package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workspace_pairs")
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
