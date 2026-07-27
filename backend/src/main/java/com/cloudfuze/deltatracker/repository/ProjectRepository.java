package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
