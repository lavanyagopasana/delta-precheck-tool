package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SignOffRepository extends JpaRepository<SignOff, Long> {

    List<SignOff> findByServerId(Long serverId);

    // Batch variant for building project/list summaries without a query per server.
    List<SignOff> findByServerIdIn(Collection<Long> serverIds);

    Optional<SignOff> findByServerIdAndRole(Long serverId, SignOffRole role);
}
