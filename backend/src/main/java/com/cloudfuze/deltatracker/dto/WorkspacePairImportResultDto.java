package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class WorkspacePairImportResultDto {

    private int totalRows;
    private int createdCount;
    private int updatedCount;
    // Rows skipped because an identical pair (all columns, including combination) was already
    // imported for the same server -- surfaced to the user in a popup so a re-uploaded file doesn't
    // silently do nothing.
    private int duplicateCount;
    private List<String> duplicates;
    private List<String> errors;
}
