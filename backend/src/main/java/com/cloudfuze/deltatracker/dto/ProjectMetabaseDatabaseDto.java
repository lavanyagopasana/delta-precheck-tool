package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// One product type's Metabase database on a project. A project carries a list of these -- one per
// product type its servers use -- because a Metabase database only ever holds one product type's data.
@Getter
@Setter
public class ProjectMetabaseDatabaseDto {

    private String productType;
    private String databaseName;
    private String setBy;
    private LocalDateTime setAt;
}
