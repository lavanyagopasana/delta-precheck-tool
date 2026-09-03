package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One evidence file frozen into a {@link DeltaCycleItem} when a cycle was snapshotted.
 *
 * <p>Mirrors {@link PreCheckItemEvidence} for history. {@code DeltaCycleItem} carries a single
 * evidence_file_path, which was the whole truth when an item could only hold one file -- afterwards
 * a cycle that had been evidenced with three files was recorded, and displayed, as one. The
 * snapshot has to be a faithful copy of what the approvers actually saw, so it needs the same
 * one-to-many shape the live item has.
 *
 * <p>Immutable in practice: rows are written once with the cycle and never edited. The file path is
 * copied rather than referenced, so deleting the live pre-check's evidence cannot alter history.
 */
@Entity
@Table(name = "delta_cycle_item_evidence", indexes = {
        @Index(name = "idx_delta_cycle_item_evidence_item", columnList = "cycle_item_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DeltaCycleItemEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_item_id", nullable = false)
    private DeltaCycleItem cycleItem;

    @Column(name = "cycle_item_id", insertable = false, updatable = false)
    private Long cycleItemId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name")
    private String fileName;

    public DeltaCycleItemEvidence(DeltaCycleItem cycleItem, String filePath, String fileName) {
        this.cycleItem = cycleItem;
        this.filePath = filePath;
        this.fileName = fileName;
    }
}
