package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One uploaded evidence file on a pre-check item. An item may have several.
 *
 * <p>A child table rather than more columns on {@link PreCheckItem}: the count is open-ended, and
 * "evidence_file_path_2, _3, _4" would cap it at whatever number somebody guessed.
 *
 * <p>{@code PreCheckItem.evidenceFilePath}/{@code evidenceFileName} deliberately survive as the
 * FIRST file of this list, kept in sync on every write. That is what lets the submit precondition,
 * the DeltaCycleItem history snapshot and every existing read carry on unchanged instead of each
 * having to learn about a second table -- and it means an item with one file behaves exactly as it
 * always did.
 */
@Entity
@Table(name = "precheck_item_evidence", indexes = {
        @Index(name = "idx_precheck_item_evidence_item", columnList = "item_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PreCheckItemEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private PreCheckItem item;

    @Column(name = "item_id", insertable = false, updatable = false)
    private Long itemId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    public PreCheckItemEvidence(PreCheckItem item, String filePath, String fileName, String uploadedBy) {
        this.item = item;
        this.filePath = filePath;
        this.fileName = fileName;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = LocalDateTime.now();
    }
}
