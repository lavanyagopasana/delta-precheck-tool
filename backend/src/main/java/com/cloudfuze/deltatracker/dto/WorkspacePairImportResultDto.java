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
    /**
     * Rows that existed for this combination before a re-upload and were removed by it.
     *
     * <p>Only ever non-zero for the per-combination re-upload, which REPLACES rather than merges.
     * Reported because "42 created" alone hides that 60 rows went away.
     */
    private int replacedCount;

    private int duplicateCount;
    private List<String> duplicates;
    private List<String> errors;
}
