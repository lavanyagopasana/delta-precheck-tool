package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "escalations")
@Getter
@Setter
@NoArgsConstructor
public class Escalation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Column(name = "ticket_number", nullable = false)
    private String ticketNumber;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscalationStatus status = EscalationStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscalationPriority priority = EscalationPriority.MEDIUM;

    @Column(name = "resolution_notes", length = 2000)
    private String resolutionNotes;

    @Column(name = "evidence_file_path")
    private String evidenceFilePath;

    @Column(name = "evidence_file_name")
    private String evidenceFileName;

    public Escalation(Server server, String ticketNumber, String description, String reason, String createdBy) {
        this.server = server;
        this.ticketNumber = ticketNumber;
        this.description = description;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.status = EscalationStatus.OPEN;
    }
}
