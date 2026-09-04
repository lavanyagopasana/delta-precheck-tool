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
     * The 2nd and 3rd approvers in the sign-off chain.
     *
     * <p>Flat lists with no team mapping, unlike engineers, because nothing scopes them: teams exist
     * to decide which engineers a manager may assign, while a Dev Lead or QA Lead approves across
     * every team. {@code AppUser.team} being nullable is what lets them sit outside the structure.
     */
    private List<String> devLeads;
    private List<String> qaLeads;

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
