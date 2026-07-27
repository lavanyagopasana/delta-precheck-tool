package com.cloudfuze.deltatracker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JoinColumn(name = "server_id", nullable = false)
    private Server server;

    @Column(name = "server_id", insertable = false, updatable = false)
    private Long serverId;

    @Column(name = "item_name", nullable = false, length = 500)
    private String itemName;

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

    public PreCheckItem(Server server, String itemName) {
        this.server = server;
        this.itemName = itemName;
        this.status = ItemStatus.NOT_STARTED;
    }
}
