package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(columnNames = {"email"}))
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppUserRole role = AppUserRole.MIGRATION_ENGINEER;

    /**
     * Every team this person belongs to. Empty is normal: ADMIN/DEV_LEAD/QA_LEAD sit outside the
     * team structure, and an engineer can be on the allowlist before anyone decides where they go.
     *
     * <p>Replaced a single {@code team_id} FK, because an engineer genuinely works across more than
     * one delivery team and the old model forced a choice that made them invisible to the other
     * team's manager. A manager may likewise hold several teams; their engineer pool is then the
     * union, which is why {@code TeamService.engineersByManager} merges rather than overwrites.
     *
     * <p>LAZY because the roster/allowlist queries read every user and almost none of them need
     * membership; consequently every caller must map this inside an open transaction.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "app_user_teams",
            joinColumns = @JoinColumn(name = "app_user_id"),
            inverseJoinColumns = @JoinColumn(name = "team_id"))
    private Set<Team> teams = new LinkedHashSet<>();

    /**
     * The pre-multi-team single membership. Retained ONLY so
     * {@code TeamMembershipBackfill} can copy it into {@link #teams} on an existing database.
     *
     * <p>It cannot simply be deleted: {@code ddl-auto=update} never drops a column, so on any
     * long-lived database (i.e. production) every current membership still lives here and nowhere
     * else until that backfill runs. Nothing else may read or write it -- the join table is the
     * single source of truth.
     *
     * @deprecated use {@link #teams}.
     */
    @Deprecated
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /**
     * Whether this person may be NAMED as a project's Migration Manager, whatever their own role.
     *
     * <p>AppUserRole is single-valued, so somebody who is an ADMIN and also runs engagements could
     * not be assigned to a project without being demoted to MIGRATION_MANAGER -- which would cost
     * them Manage Access and the admin-only pre-check unblock path. This is deliberately a
     * capability an admin toggles, not a second role: authorization still derives from {@link #role}
     * alone, and this only widens who appears in the manager picker.
     *
     * <p>Defaults to false, so an existing row keeps behaving exactly as before until an admin says
     * otherwise. The column default matters as much as the field one -- ddl-auto=update adds this
     * column to a long-lived database, where every existing row needs a value for the NOT NULL.
     */
    @Column(name = "assignable_as_manager", nullable = false, columnDefinition = "boolean default false")
    private boolean assignableAsManager = false;

    @Column(name = "added_by")
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt = LocalDateTime.now();

    public AppUser(String email, AppUserRole role, String addedBy) {
        this.email = email.toLowerCase();
        this.role = role;
        this.addedBy = addedBy;
        this.addedAt = LocalDateTime.now();
    }
}
