package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // Team names are matched case-insensitively for the same reason emails are: they're typed by
    // hand into a CSV cell, so "team 4" and "Team 4" must resolve to the same row.
    Optional<Team> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Team> findAllByOrderByNameAsc();
}
