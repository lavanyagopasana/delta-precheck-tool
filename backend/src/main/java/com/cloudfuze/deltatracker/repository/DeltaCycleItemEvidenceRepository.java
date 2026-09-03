package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.DeltaCycleItemEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeltaCycleItemEvidenceRepository extends JpaRepository<DeltaCycleItemEvidence, Long> {

    List<DeltaCycleItemEvidence> findByCycleItemIdOrderByIdAsc(Long cycleItemId);

    // One query per history panel rather than one per item on it.
    List<DeltaCycleItemEvidence> findByCycleItemIdInOrderByIdAsc(List<Long> cycleItemIds);
}
