package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.ProjectMetabaseDatabase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMetabaseDatabaseRepository extends JpaRepository<ProjectMetabaseDatabase, Long> {

    List<ProjectMetabaseDatabase> findByProjectId(Long projectId);

    Optional<ProjectMetabaseDatabase> findByProjectIdAndProductType(Long projectId, ProductType productType);

    void deleteByProjectId(Long projectId);
}
