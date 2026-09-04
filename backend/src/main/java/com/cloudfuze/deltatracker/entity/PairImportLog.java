package com.cloudfuze.deltatracker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One user-mapping CSV upload: who uploaded what, when, and what it did to the pair list.
 *
 * <p>Matters more than it looks. A re-upload REPLACES the combination's pairs, so without this row
 * there is no record anywhere of what a re-upload removed -- the previous rows are simply gone. The
 * {@link #replacedCount} is the part that could not be reconstructed afterwards from anything else.
 *
 * <p>Separate from {@code ChangeLogEntry} because an import is not a field changing value: it has a
 * filename and row counts, and folding it in would leave half of either table's columns null on
 * every row.
 */
@Entity
@Table(name = "pair_import_log", indexes = {
        @Index(name = "idx_pair_import_log_server", columnList = "server_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PairImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** Null for a whole-project import, whose rows carry their own combination per row. */
    @Column(name = "combination", length = 200)
    private String combination;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "total_rows", nullable = false, columnDefinition = "int default 0")
    private int totalRows;

    @Column(name = "created_count", nullable = false, columnDefinition = "int default 0")
    private int createdCount;

    @Column(name = "updated_count", nullable = false, columnDefinition = "int default 0")
    private int updatedCount;

    /** Rows that existed before this upload and were removed by it. The irreplaceable number. */
    @Column(name = "replaced_count", nullable = false, columnDefinition = "int default 0")
    private int replacedCount;

    @Column(name = "duplicate_count", nullable = false, columnDefinition = "int default 0")
    private int duplicateCount;

    @Column(name = "error_count", nullable = false, columnDefinition = "int default 0")
    private int errorCount;

    @Column(name = "imported_by")
    private String importedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "imported_by_role")
    private AppUserRole importedByRole;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt = LocalDateTime.now();
}
