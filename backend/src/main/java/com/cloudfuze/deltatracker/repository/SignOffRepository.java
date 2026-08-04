package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SignOffRepository extends JpaRepository<SignOff, Long> {

    List<SignOff> findByCombinationId(Long combinationId);

    // Batch variant for building project/list summaries without a query per combination.
    List<SignOff> findByCombinationIdIn(Collection<Long> combinationIds);

    Optional<SignOff> findByCombinationIdAndRole(Long combinationId, SignOffRole role);
}
