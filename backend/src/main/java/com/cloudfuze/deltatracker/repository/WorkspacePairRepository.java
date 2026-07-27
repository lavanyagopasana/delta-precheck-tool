package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.WorkspacePair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspacePairRepository extends JpaRepository<WorkspacePair, Long> {

    List<WorkspacePair> findByServerId(Long serverId);

    Optional<WorkspacePair> findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPath(
            Long serverId, String sourceEmail, String sourcePath, String destinationEmail, String destinationPath);
}
