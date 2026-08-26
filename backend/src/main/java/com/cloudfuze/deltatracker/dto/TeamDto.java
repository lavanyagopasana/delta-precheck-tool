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

    public static TeamDto fromEntity(Team team, List<String> memberEmails) {
        TeamDto dto = new TeamDto();
        dto.setId(team.getId());
        dto.setName(team.getName());
        dto.setCreatedBy(team.getCreatedBy());
        dto.setCreatedAt(team.getCreatedAt());
        dto.setMemberEmails(memberEmails);
        return dto;
    }
}
