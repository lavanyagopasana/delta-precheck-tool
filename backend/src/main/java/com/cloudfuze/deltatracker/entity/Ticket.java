package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A tracked ticket (e.g. a Jira issue) raised against a server. Deliberately minimal: the source of
 * truth for details lives in the external ticket system, so we only store a link to it plus the
 * bare tracking state (open vs resolved) needed to surface it on the dashboard and NavBar badge.
 */
@Entity
@Table(name = "tickets", indexes = {
        // Backs countByStatus(OPEN) (NavBar polls the open-ticket count every 30s).
        @Index(name = "idx_ticket_status", columnList = "status"),
        // Backs the duplicate-URL check on create/update.
        @Index(name = "idx_ticket_url", columnList = "ticket_url")
})
@Getter
@Setter
@NoArgsConstructor
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic-lock guard: concurrent resolve/update on the same ticket won't silently overwrite
    // one another -- the losing flush fails with an optimistic-lock exception (mapped to HTTP 409).
    // Defaulted to 0 for rows that predate this column (the ALTER adds it with DEFAULT 0).
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    // 512 chosen so idx_ticket_url below is creatable on MySQL/InnoDB utf8mb4 (512*4 = 2048 bytes,
    // under the 3072-byte key limit). At 2000 the CREATE INDEX silently failed under ddl-auto=update,
    // leaving the duplicate-URL lookup unindexed. Ticket URLs are short in practice (observed max 17).
    @Column(name = "ticket_url", nullable = false, length = 512)
    private String ticketUrl;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status = TicketStatus.OPEN;

    public Ticket(Server server, String ticketUrl, String createdBy) {
        this.server = server;
        this.ticketUrl = ticketUrl;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.status = TicketStatus.OPEN;
    }
}
