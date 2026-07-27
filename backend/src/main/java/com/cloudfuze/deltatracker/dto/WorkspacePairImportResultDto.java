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
    private List<String> errors;
}
