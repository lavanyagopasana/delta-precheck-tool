package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(columnNames = {"name"}))
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "migration_manager_name")
    private String migrationManagerName;

    // Project team members (drawn from the MIGRATION_ENGINEER roster). Whoever works the servers
    // in this project isn't tracked per-server -- just membership at the project level.
    @ElementCollection
    @CollectionTable(name = "project_engineers", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "email")
    private Set<String> engineerEmails = new LinkedHashSet<>();

    // Set only on projects that came from the PMO tool's /api/external/projects feed; null means a
    // project somebody created here by hand. This is what makes the sync idempotent: PmoSyncService
    // matches on it, never on name, because a project renamed in PMO would otherwise be imported a
    // second time and the original -- holding all the servers, checklists and sign-off history --
    // would sit orphaned next to it. Unique so two PMO records can never collapse onto one project.
    @Column(name = "external_id", unique = true)
    private String externalId;

    // When the sync last saw this project in the PMO feed. Purely diagnostic (it answers "is the poll
    // actually running?"), never used to decide anything -- a project that stops appearing in the feed
    // is deliberately left alone rather than deleted, since ours holds migration history PMO doesn't.
    @Column(name = "external_synced_at")
    private LocalDateTime externalSyncedAt;

    // Read-only context copied from PMO so whoever triages a freshly synced project can see what it
    // is without opening the other tool. None of these drive any behaviour here -- they are display
    // fields, refreshed on every poll, and this tool remains the authority on everything it owns.
    //
    // externalManagerName in particular is NOT a substitute for migrationManagerName: PMO reports its
    // project manager as a display name ("Harika"), while migrationManagerName is compared as an
    // email address by ProjectService.isVisible and by the whole sign-off chain. Writing a display
    // name into that field would silently break both, so PMO's manager is kept here instead, purely
    // as a hint about who an admin should assign.
    @Column(name = "external_manager_name")
    private String externalManagerName;

    @Column(name = "external_customer_name")
    private String externalCustomerName;

    @Column(name = "external_status")
    private String externalStatus;

    @Column(name = "external_phase")
    private String externalPhase;

    // PMO's comma-separated "Source - Destination" list, e.g. "MyDrive - MyDrive, Shared Drive -
    // Shared Drive". Kept because it is also what disambiguates the 31 PMO project names that repeat
    // (the same customer split by migration type) -- see PmoSyncService.assignNames.
    @Column(name = "external_migration_types", length = 1000)
    private String externalMigrationTypes;

    // Which database in Metabase (https://metabase.cloudfuze.com/browse/databases) holds this
    // project's migration data. Entered by hand because nothing links a Metabase database back to a
    // PMO project: the databases are named per customer/engagement by whoever provisioned them, and
    // neither PMO's project id nor its name is derivable from that. Null until somebody fills it in,
    // which is also what the "Get process status" button treats as "not configured yet".
    @Column(name = "metabase_database_name")
    private String metabaseDatabaseName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Project(String name, String createdBy, String migrationManagerName,
                    Set<String> engineerEmails) {
        this.name = name;
        this.createdBy = createdBy;
        this.migrationManagerName = migrationManagerName;
        if (engineerEmails != null) {
            this.engineerEmails = engineerEmails;
        }
        this.createdAt = LocalDateTime.now();
    }
}
