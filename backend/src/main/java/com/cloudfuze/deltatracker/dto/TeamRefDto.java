package com.cloudfuze.deltatracker.dto;

import lombok.Getter;

/**
 * A team as it appears inside another response -- id and name only.
 *
 * <p>Deliberately not {@link TeamDto}, which carries its member lists: an allowlist of 79 people
 * would then repeat every team's whole membership once per member.
 */
@Getter
public class TeamRefDto {

    private final Long id;
    private final String name;

    public TeamRefDto(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
