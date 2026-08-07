package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeltaCycleItemRepository extends JpaRepository<DeltaCycleItem, Long> {

    List<DeltaCycleItem> findByCycleIdOrderBySortOrderAsc(Long cycleId);

    // Batch variant so rendering a combination's whole history costs one query for all its cycles'
    // items instead of one per cycle.
    List<DeltaCycleItem> findByCycleIdInOrderBySortOrderAsc(Collection<Long> cycleIds);
}
