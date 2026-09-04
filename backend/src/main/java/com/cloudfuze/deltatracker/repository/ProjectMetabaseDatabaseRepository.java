package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.ProjectMetabaseDatabase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMetabaseDatabaseRepository extends JpaRepository<ProjectMetabaseDatabase, Long> {

    List<ProjectMetabaseDatabase> findByProjectId(Long projectId);

    // A list now, not an Optional: a product type can be spread across several Metabase databases.
    List<ProjectMetabaseDatabase> findByProjectIdAndProductType(Long projectId, ProductType productType);

    // Case-insensitive because Metabase's own names are, and adding "Bakkt" beside "bakkt" would
    // double every figure for that product type rather than reading as the duplicate it is.
    Optional<ProjectMetabaseDatabase> findByProjectIdAndProductTypeAndDatabaseNameIgnoreCase(
            Long projectId, ProductType productType, String databaseName);

    void deleteByProjectId(Long projectId);
}
