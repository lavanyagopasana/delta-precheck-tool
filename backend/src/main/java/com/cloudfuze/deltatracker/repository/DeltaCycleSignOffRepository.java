package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeltaCycleSignOffRepository extends JpaRepository<DeltaCycleSignOff, Long> {

    List<DeltaCycleSignOff> findByCycleId(Long cycleId);

    // Batch variant -- see DeltaCycleItemRepository.findByCycleIdInOrderBySortOrderAsc.
    List<DeltaCycleSignOff> findByCycleIdIn(Collection<Long> cycleIds);
}
