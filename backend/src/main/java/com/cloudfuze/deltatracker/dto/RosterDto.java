package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RosterDto {

    private List<String> migrationManagers;
    private List<String> engineers;
}
