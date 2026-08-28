package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

// One database as Metabase reports it at GET /api/database -- what the project page's dropdown is
// built from.
//
// The id is carried even though a project stores only the NAME (see Project.metabaseDatabaseName).
// Name is what a human recognises and what the field is specified in terms of; the id is here because
// querying a database later needs it, and resolving name -> id is a lookup against this same list.
@Getter
@Setter
public class MetabaseDatabaseDto {

    private Long id;
    private String name;
    // Metabase's driver name ("mysql", "postgres", ...). Shown next to the name in the dropdown
    // because several CloudFuze databases differ only by engine.
    private String engine;
}
