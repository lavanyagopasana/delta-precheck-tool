package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PreCheckSubmissionRepository extends JpaRepository<PreCheckSubmission, Long> {

    Optional<PreCheckSubmission> findByServerId(Long serverId);
}
