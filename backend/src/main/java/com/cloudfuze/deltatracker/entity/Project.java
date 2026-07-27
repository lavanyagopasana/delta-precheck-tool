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

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private ProductType productType;

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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Project(String name, ProductType productType, String createdBy, String migrationManagerName,
                    Set<String> engineerEmails) {
        this.name = name;
        this.productType = productType;
        this.createdBy = createdBy;
        this.migrationManagerName = migrationManagerName;
        if (engineerEmails != null) {
            this.engineerEmails = engineerEmails;
        }
        this.createdAt = LocalDateTime.now();
    }
}
