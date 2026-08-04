package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspaceCombinationRepository extends JpaRepository<WorkspaceCombination, Long> {

    List<WorkspaceCombination> findByServerId(Long serverId);

    // Batch variant for building project/list summaries without a query per server.
    List<WorkspaceCombination> findByServerIdIn(Collection<Long> serverIds);

    // Upsert lookup used when a CSV is imported under a given combination name -- creates the row
    // the first time that name is seen for this server, reuses it every time after.
    Optional<WorkspaceCombination> findByServerIdAndNameIgnoreCase(Long serverId, String name);
}
