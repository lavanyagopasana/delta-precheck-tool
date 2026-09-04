package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// A frozen copy of one PreCheckItem as it stood when its cycle was approved. Written once (see
// DeltaCycle's javadoc) and never updated -- that immutability is the point: it's what the approvers
// actually signed off on, so it must not change when the live item is reset for the next cycle.
// evidenceFilePath still points at the same file under uploads/, which a rollover never deletes.
@Entity
// The Delta history panel loads items by cycle, often for several cycles at once
// (findByCycleIdInOrderBySortOrderAsc). sort_order is included so that ordering is served by the
// index rather than a separate sort step, and cycle_id alone is still covered by the leading column.
@Table(name = "delta_cycle_items", indexes = {
        @Index(name = "idx_cycle_item_cycle_sort", columnList = "cycle_id, sort_order")
})
@Getter
@Setter
@NoArgsConstructor
public class DeltaCycleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private DeltaCycle cycle;

    @Column(name = "cycle_id", insertable = false, updatable = false)
    private Long cycleId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

    // VARCHAR for the same reason as PreCheckItem.status -- a snapshot copies whatever the live item
    // held, so it hits the identical truncation on any newly added ItemStatus value.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status;

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

    // Preserves the checklist's display order (ServerService.preCheckItemsFor) so the snapshot reads
    // the same as the live form did, without re-deriving it from a product type that may have been
    // edited since.
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public DeltaCycleItem(DeltaCycle cycle, PreCheckItem source, int sortOrder) {
        this.cycle = cycle;
        this.itemName = source.getItemName();
        this.status = source.getStatus();
        this.notes = source.getNotes();
        this.evidenceFilePath = source.getEvidenceFilePath();
        this.evidenceFileName = source.getEvidenceFileName();
        this.lastModifiedBy = source.getLastModifiedBy();
        this.lastModifiedAt = source.getLastModifiedAt();
        this.sortOrder = sortOrder;
    }
}
