package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class AppUserDto {

    private Long id;
    private String email;
    private AppUserRole role;
    private String addedBy;
    private LocalDateTime addedAt;
    /** Every team this person is on. Empty is normal -- see AppUser.teams. */
    private List<TeamRefDto> teams = List.of();

    /**
     * The FIRST of {@link #teams}, kept so a display expecting a single team still renders. It is a
     * compatibility shim, not the truth: somebody on three teams reports one here.
     *
     * @deprecated read {@link #teams}.
     */
    @Deprecated
    private Long teamId;

    /** @deprecated read {@link #teams}. */
    @Deprecated
    private String teamName;
    // Whether this person is offered in the project Migration Manager picker on top of their role.
    private boolean assignableAsManager;

    public static AppUserDto fromEntity(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        dto.setAddedBy(user.getAddedBy());
        dto.setAddedAt(user.getAddedAt());
        dto.setAssignableAsManager(user.isAssignableAsManager());
        // Reading through the lazy collection is safe here: every caller maps inside an open
        // transaction (AppUserService is @Transactional), and empty simply means "no team".
        dto.setTeams(user.getTeams().stream()
                .map(team -> new TeamRefDto(team.getId(), team.getName()))
                .toList());
        dto.getTeams().stream().findFirst().ifPresent(first -> {
            dto.setTeamId(first.getId());
            dto.setTeamName(first.getName());
        });
        return dto;
    }
}
