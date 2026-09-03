package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.ChangeLogEntityType;
import com.cloudfuze.deltatracker.entity.ChangeLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeLogEntryRepository extends JpaRepository<ChangeLogEntry, Long> {

    // Newest first: the trail answers "who changed this last", which should not need scrolling.
    List<ChangeLogEntry> findByEntityTypeAndEntityIdOrderByChangedAtDescIdDesc(
            ChangeLogEntityType entityType, Long entityId);
}
