package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class RosterDto {

    private List<String> migrationManagers;
    private List<String> engineers;

    /**
     * manager email (lowercase) -> engineer emails on that manager's team.
     *
     * <p>Sent alongside the flat lists rather than replacing them, so the caller can scope a
     * dropdown to one manager AND still fall back to {@code engineers} when that manager has no
     * entry here. Shipping both in one payload also means the project dashboard needs no second
     * round trip when its manager changes.
     */
    private Map<String, List<String>> engineersByManager;
}
