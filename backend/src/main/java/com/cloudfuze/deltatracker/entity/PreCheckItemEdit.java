package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One edit to a pre-check item: who changed it, when, and what changed.
 *
 * <p>Append-only. Nothing updates or deletes a row, which is the whole point: {@code PreCheckItem}
 * already carried lastModifiedBy/lastModifiedAt, but those are overwritten on every save, so the
 * moment a second person touched an item the first was gone. This keeps the chain.
 *
 * <p>Exists because pre-check editing was opened to Migration Managers, who are also the first
 * approver in the sign-off chain. Rather than forbid that overlap, it is disclosed: every edit is
 * attributed and shown to everyone who can see the item.
 *
 * <p>{@link #editedByRole} is recorded AS IT WAS at edit time, not looked up when the trail is
 * read. An admin who later becomes an engineer must not retroactively turn their admin-era edits
 * into engineer edits.
 */
@Entity
@Table(name = "precheck_item_edits", indexes = {
        @Index(name = "idx_precheck_item_edit_item", columnList = "item_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PreCheckItemEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private PreCheckItem item;

    @Column(name = "item_id", insertable = false, updatable = false)
    private Long itemId;

    @Column(name = "edited_by")
    private String editedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "edited_by_role")
    private AppUserRole editedByRole;

    @Column(name = "edited_at", nullable = false)
    private LocalDateTime editedAt = LocalDateTime.now();

    /** Null when the status did not change, so the trail can say "note only" honestly. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ItemStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private ItemStatus toStatus;

    @Column(name = "notes_changed", nullable = false, columnDefinition = "boolean default false")
    private boolean notesChanged;

    @Column(name = "evidence_added", nullable = false, columnDefinition = "int default 0")
    private int evidenceAdded;

    @Column(name = "evidence_removed", nullable = false, columnDefinition = "int default 0")
    private int evidenceRemoved;

    public PreCheckItemEdit(PreCheckItem item, String editedBy, AppUserRole editedByRole) {
        this.item = item;
        this.editedBy = editedBy;
        this.editedByRole = editedByRole;
        this.editedAt = LocalDateTime.now();
    }

    /** Whether anything actually changed -- a save that altered nothing is not worth a row. */
    public boolean isSomethingChanged() {
        return fromStatus != toStatus || notesChanged || evidenceAdded > 0 || evidenceRemoved > 0;
    }
}
