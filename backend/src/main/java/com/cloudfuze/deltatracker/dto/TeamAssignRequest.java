package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TeamAssignRequest {

    @NotBlank(message = "An email is required.")
    private String email;

    /**
     * The full set of teams this person should be on. An empty list takes them off every team; the
     * list is meaningful rather than a missing value, which is why it is sent explicitly.
     */
    private List<Long> teamIds;

    /**
     * The pre-multi-team single field. Still honoured so an older client (or a bookmarked request)
     * does not silently no-op: {@link #resolvedTeamIds()} falls back to it when {@code teamIds} is
     * absent. Prefer {@code teamIds}.
     *
     * @deprecated use {@link #teamIds}.
     */
    @Deprecated
    private Long teamId;

    /** {@code teamIds} when present, else the legacy single {@code teamId}, else empty. */
    public List<Long> resolvedTeamIds() {
        if (teamIds != null) {
            return teamIds;
        }
        return teamId == null ? List.of() : List.of(teamId);
    }
}
