package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.PairImportLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PairImportLogRepository extends JpaRepository<PairImportLog, Long> {

    List<PairImportLog> findByServerIdOrderByImportedAtDescIdDesc(Long serverId);

    // The per-combination panel shows only that combination's uploads; a whole-project import has a
    // null combination and is therefore not attributed to any single one.
    List<PairImportLog> findByServerIdAndCombinationIgnoreCaseOrderByImportedAtDescIdDesc(
            Long serverId, String combination);
}
