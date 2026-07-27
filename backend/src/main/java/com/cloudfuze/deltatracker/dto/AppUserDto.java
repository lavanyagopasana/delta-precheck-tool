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

    public static AppUserDto fromEntity(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        dto.setAddedBy(user.getAddedBy());
        dto.setAddedAt(user.getAddedAt());
        return dto;
    }
}
