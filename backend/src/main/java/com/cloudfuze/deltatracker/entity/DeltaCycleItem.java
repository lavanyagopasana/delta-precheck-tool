package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "delta_cycle_items")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemStatus status;

    @Column(length = 2000)
    private String notes;

    @Column(name = "evidence_file_path")
    private String evidenceFilePath;

    @Column(name = "evidence_file_name")
    private String evidenceFileName;

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
