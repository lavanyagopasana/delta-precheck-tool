package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.WorkspacePair;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspacePairRepository extends JpaRepository<WorkspacePair, Long> {

    List<WorkspacePair> findByServerId(Long serverId);

    // Capped variant (SQL LIMIT via Pageable) for the display list endpoint, so a server with a huge
    // CSV import can't force an unbounded result set onto the wire. See WorkspacePairService.listByServer.
    List<WorkspacePair> findByServerId(Long serverId, Pageable pageable);

    // Row count without materializing every entity -- used for the display cap check and for
    // stamping Server.totalPairCount after an import.
    long countByServerId(Long serverId);

    // Batch variant for building project/list summaries without a query per server.
    List<WorkspacePair> findByServerIdIn(Collection<Long> serverIds);

    // The real identity key for a pair includes combination -- the same source/destination email+path
    // legitimately represents two distinct pairs under two different combinations (e.g. the same
    // person's Box account AND Google Drive account both migrating to the same OneDrive mailbox).
    // Spring Data turns a null `combination` argument into "IS NULL" automatically, so this still
    // matches correctly for pairs imported without a combination at all.
    Optional<WorkspacePair> findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPathAndCombination(
            Long serverId, String sourceEmail, String sourcePath, String destinationEmail, String destinationPath,
            String combination);

    // Backs "delete this combination" on the project page's per-server combination list.
    List<WorkspacePair> findByServerIdAndCombinationIgnoreCase(Long serverId, String combination);
}
