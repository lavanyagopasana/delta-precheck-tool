package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PreCheckSubmissionRepository extends JpaRepository<PreCheckSubmission, Long> {

    Optional<PreCheckSubmission> findByServerId(Long serverId);

    // Batch variant for building project/list summaries without a query per server.
    List<PreCheckSubmission> findByServerIdIn(Collection<Long> serverIds);
}
