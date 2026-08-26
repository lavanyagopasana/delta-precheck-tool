package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AppUserImportResultDto {

    private int totalRows;
    private int createdCount;
    private int updatedCount;
    private List<String> errors;

    /**
     * Names of teams the import had to create because they did not exist yet.
     *
     * <p>Reported rather than silent so a mistyped team cell is visible immediately -- it shows up
     * here as a team nobody meant to create, instead of quietly scoping somebody's engineer dropdown
     * to the wrong group weeks later.
     */
    private List<String> createdTeams;
}
