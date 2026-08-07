package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeltaCycleRepository extends JpaRepository<DeltaCycle, Long> {

    List<DeltaCycle> findByCombinationIdOrderByCycleNumberAsc(Long combinationId);

    // The current cycle's row -- i.e. the highest-numbered one, since a new row only appears once the
    // previous cycle has finished and rolled over. Used to stamp Start/Finish onto the cycle record.
    Optional<DeltaCycle> findFirstByCombinationIdOrderByCycleNumberDesc(Long combinationId);

    // Batch variant for building project/server rollups without a query per combination -- mirrors
    // PreCheckSubmissionRepository.findByCombinationIdIn.
    List<DeltaCycle> findByCombinationIdIn(Collection<Long> combinationIds);

    long countByCombinationId(Long combinationId);
}
