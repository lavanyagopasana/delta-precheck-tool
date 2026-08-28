package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

// One row of the processStatus breakdown: how many workspaces sit in this state.
//
// The status vocabulary is NOT the same across product types -- email reports
// PROCESSED_WITH_CONFLICTS / PROCESSED_WITH_FOLDER_CONFLICT / PAUSE where message reports
// PROCESSED_WITH_SOME_CONFLICTS / SUSPENDED (verified against 11 real databases on 2026-08-27). So
// status is carried as the raw string Metabase gave us, never coerced into an enum: an unrecognised
// value has to reach the screen rather than being dropped, because a dropped row understates conflicts.
@Getter
@Setter
public class MetabaseStatusCountDto {

    private String status;
    private long count;

    public MetabaseStatusCountDto() {
    }

    public MetabaseStatusCountDto(String status, long count) {
        this.status = status;
        this.count = count;
    }
}
