package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
// Every pre-check read (the checklist itself, completedCount, the submit preconditions) filters by
// combination_id, and unlike sign_offs/precheck_submissions/delta_cycles there is no unique
// constraint on this table whose leading column would cover it -- so these were sequential scans.
@Table(name = "precheck_items", indexes = {
        @Index(name = "idx_precheck_item_combination", columnList = "combination_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PreCheckItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combination_id", nullable = false)
    private WorkspaceCombination combination;

    @Column(name = "combination_id", insertable = false, updatable = false)
    private Long combinationId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    // VARCHAR, not MySQL's native ENUM. Hibernate 6 maps @Enumerated(STRING) to a native enum column
    // by default, and ddl-auto=update never widens one -- so adding an ItemStatus value made every
    // save of that value fail with "Data truncated for column 'status'" until the column was altered
    // by hand. As VARCHAR, a new enum constant needs no schema change at all.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status = ItemStatus.NOT_STARTED;

    @Column(length = 2000)
    private String notes;

    @Column(name = "evidence_file_path")
    private String evidenceFilePath;

    @Column(name = "evidence_file_name")
    private String evidenceFileName;

    /**
     * Evidence captured from Metabase rather than uploaded as a file, stored as JSON: the status
     * counts, the database and collection they came from, the aggregation pipeline that produced
     * them, and who captured them when.
     *
     * <p>Why this exists alongside evidenceFilePath rather than replacing it: a screenshot is a
     * photograph of a number. It cannot be re-checked, it cannot be compared against the same query
     * run later, and -- as six of them proved on 2026-08-29 -- it lives in a directory nothing backs
     * up, so deleting a project destroyed them permanently. This column is in Postgres, which every
     * deploy dumps.
     *
     * <p>Deliberately a text column holding JSON, not a set of typed columns. The shape differs per
     * checklist item (workspace counts, drive-change counts, permission counts), and a column per
     * variant would mean a schema change every time a new item is wired up.
     */
    @Column(name = "evidence_data", columnDefinition = "text")
    private String evidenceData;

    /** When {@link #evidenceData} was captured -- an approver needs to know how stale the figures are. */
    @Column(name = "evidence_captured_at")
    private LocalDateTime evidenceCapturedAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    public PreCheckItem(WorkspaceCombination combination, String itemName) {
        this.combination = combination;
        this.itemName = itemName;
        this.status = ItemStatus.NOT_STARTED;
    }
}
