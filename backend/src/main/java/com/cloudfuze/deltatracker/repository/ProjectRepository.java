package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    // Backs the PMO sync's upsert. Matching on externalId rather than name is what makes a rename in
    // PMO an update here instead of a duplicate project -- see Project.externalId.
    Optional<Project> findByExternalId(String externalId);

    // Lets the PMO sync tell "this name is taken by ME" from "taken by a different project" before it
    // trips the UNIQUE constraint on name -- existsByNameIgnoreCase can't distinguish the two.
    Optional<Project> findByNameIgnoreCase(String name);
}
