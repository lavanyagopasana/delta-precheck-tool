package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sign_offs", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "role"}))
@Getter
@Setter
@NoArgsConstructor
public class SignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffRole role;

    @Column(name = "signed_by", nullable = false)
    private String signedBy;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignOffStatus status = SignOffStatus.PENDING;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // Only ever set on the Dev Lead row, at the moment the Dev Lead approves -- records whether they
    // said QA Lead approval is required for this server. Null means the Dev Lead hasn't acted yet.
    @Column(name = "qa_required")
    private Boolean qaRequired;

    public SignOff(Server server, SignOffRole role, String signedBy) {
        this.server = server;
        this.role = role;
        this.signedBy = signedBy;
        this.signedAt = LocalDateTime.now();
        this.status = SignOffStatus.PENDING;
    }
}
