package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which Metabase database holds a project's migration data, for ONE product type.
 *
 * <p>Per product type rather than per project because a Metabase database only ever contains one
 * product type's data -- verified across 14 databases on 2026-08-27: each holds exactly one of
 * {@code MessageWorkSpace} / {@code MoveWorkSpaces} / {@code emailWorkSpace}, never two. One customer
 * therefore gets several databases (`bakktmsg`, `bakkt`, `bakktemail`), so a project whose servers
 * span product types needs one name per type. A single-product-type project just has one row here,
 * which is every project PMO produces today (190 of 190 are single-type).
 *
 * <p>Replaces the earlier single {@code Project.metabaseDatabaseName} column. That column is left in
 * the database by {@code ddl-auto=update} (it drops nothing) but is no longer mapped or read.
 *
 * <p>The unique constraint is what makes "one database per product type per project" a database-level
 * fact rather than a service-level hope.
 */
@Entity
@Table(name = "project_metabase_databases",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "product_type"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectMetabaseDatabase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType;

    @Column(name = "database_name", nullable = false)
    private String databaseName;

    // Who fixed it and when. Kept because confirming a database is a one-way action for everyone but
    // an admin (see ProjectService.setMetabaseDatabase) -- if the wrong one is chosen, the question
    // asked next is who chose it.
    @Column(name = "set_by")
    private String setBy;

    @Column(name = "set_at")
    private java.time.LocalDateTime setAt;

    public ProjectMetabaseDatabase(Project project, ProductType productType, String databaseName,
                                    String setBy) {
        this.project = project;
        this.productType = productType;
        this.databaseName = databaseName;
        this.setBy = setBy;
        this.setAt = java.time.LocalDateTime.now();
    }
}
