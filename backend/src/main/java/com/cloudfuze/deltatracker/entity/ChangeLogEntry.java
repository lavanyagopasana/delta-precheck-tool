package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One recorded change to a project, server or combination: who changed what, from what, to what.
 *
 * <p>Deliberately ONE table rather than a per-aggregate audit entity. Project, Server and
 * WorkspaceCombination edits are the same shape -- a field went from one value to another -- and
 * three near-identical tables, repositories, DTOs and endpoints would have been the same code
 * written three times, drifting apart the first time one of them gained a column. {@link #entityType}
 * plus {@link #entityId} is what a per-table foreign key would have given, at the cost of no
 * database-level referential integrity: a deleted project leaves its trail behind, which for an
 * audit record is the right way round.
 *
 * <p>CSV imports are NOT recorded here. A pair import is not a field changing value -- it has a
 * filename and row counts -- so it has its own {@code PairImportLog}. Forcing both into one table
 * would have meant half the columns being null on every row.
 *
 * <p>Append-only, and {@link #changedByRole} is the role AS IT WAS at the time: a person's later
 * promotion must not rewrite what they did as an engineer.
 */
@Entity
@Table(name = "change_log", indexes = {
        @Index(name = "idx_change_log_entity", columnList = "entity_type,entity_id")
})
@Getter
@Setter
@NoArgsConstructor
public class ChangeLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private ChangeLogEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "field_name", nullable = false, length = 100)
    private String fieldName;

    // Long enough for a project name, a server URL or a combination name, and truncated by the
    // recorder rather than rejected -- losing the tail of an old value must never fail the edit
    // itself.
    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "changed_by")
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role")
    private AppUserRole changedByRole;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();

    public ChangeLogEntry(ChangeLogEntityType entityType, Long entityId, String fieldName,
                           String oldValue, String newValue, String changedBy, AppUserRole changedByRole) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedByRole = changedByRole;
        this.changedAt = LocalDateTime.now();
    }
}
