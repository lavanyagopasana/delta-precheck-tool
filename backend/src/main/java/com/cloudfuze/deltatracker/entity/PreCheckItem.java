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
@Table(name = "precheck_items")
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
