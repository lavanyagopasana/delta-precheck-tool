package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A delivery team: one or more Migration Managers plus the Migration Engineers who work for them.
 *
 * <p>Membership lives on {@link AppUser#getTeam()}, not in a list here, and who is a manager of a
 * team is DERIVED from each member's {@link AppUser#getRole()} rather than stored a second time.
 * That matters: a separate manager-email list on this entity would duplicate identity already held
 * in app_users.role and could drift out of sync with it -- the same class of bug the two
 * APPROVAL_SEQUENCE copies already cause elsewhere in this codebase. Deriving also means a team
 * with two managers (Teams 5 and 6 in the real roster) needs no extra modelling at all.
 *
 * <p>team_id on app_users is nullable on purpose. ADMIN, DEV_LEAD and QA_LEAD sit outside the
 * team structure entirely, and an engineer can exist before anyone decides which team they join.
 */
@Entity
@Table(name = "teams", uniqueConstraints = @UniqueConstraint(columnNames = {"name"}))
@Getter
@Setter
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Team(String name, String createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }
}
