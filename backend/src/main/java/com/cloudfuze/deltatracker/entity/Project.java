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

    // The specific Dev Lead / QA Lead who owns this project's approvals. Both nullable, and null is
    // meaningful: it means "nobody is assigned", in which case any holder of that role may act --
    // which is how every project behaved before this existed. Existing projects therefore keep
    // working untouched, and assigning someone narrows the chain rather than unblocking it.
    //
    // Single values, not lists like engineers: the chain has exactly one Dev Lead step and one QA
    // Lead step, so two people holding a step would recreate the ambiguity this replaces. Leads sit
    // outside the Team structure on purpose -- the same Dev/QA Lead commonly covers every team, so
    // scoping them by team would be wrong.
    @Column(name = "dev_lead_email")
    private String devLeadEmail;

    @Column(name = "qa_lead_email")
    private String qaLeadEmail;

    // Project team members (drawn from the MIGRATION_ENGINEER roster). Whoever works the servers
    // in this project isn't tracked per-server -- just membership at the project level.
    @ElementCollection
    @CollectionTable(name = "project_engineers", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "email")
    private Set<String> engineerEmails = new LinkedHashSet<>();

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
