package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AppUserDto {

    private Long id;
    private String email;
    private AppUserRole role;
    private String addedBy;
    private LocalDateTime addedAt;
    private Long teamId;
    private String teamName;

    public static AppUserDto fromEntity(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        dto.setAddedBy(user.getAddedBy());
        dto.setAddedAt(user.getAddedAt());
        // Reading through the lazy proxy is safe here: every caller maps inside an open
        // transaction (AppUserService is @Transactional), and null simply means "no team".
        if (user.getTeam() != null) {
            dto.setTeamId(user.getTeam().getId());
            dto.setTeamName(user.getTeam().getName());
        }
        return dto;
    }
}
