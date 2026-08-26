package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.Team;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class TeamDto {

    private Long id;
    private String name;
    private String createdBy;
    private LocalDateTime createdAt;
    private List<String> memberEmails;

    /**
     * Split out from memberEmails because "Team 4" identifies nothing to a human -- people know a
     * team by who runs it. The UI labels every team with these, so a team with two managers reads as
     * both names rather than picking one arbitrarily.
     */
    private List<String> managerEmails;
    private List<String> engineerEmails;

    public static TeamDto fromEntity(Team team, List<String> memberEmails,
                                      List<String> managerEmails, List<String> engineerEmails) {
        TeamDto dto = new TeamDto();
        dto.setId(team.getId());
        dto.setName(team.getName());
        dto.setCreatedBy(team.getCreatedBy());
        dto.setCreatedAt(team.getCreatedAt());
        dto.setMemberEmails(memberEmails);
        dto.setManagerEmails(managerEmails);
        dto.setEngineerEmails(engineerEmails);
        return dto;
    }
}
