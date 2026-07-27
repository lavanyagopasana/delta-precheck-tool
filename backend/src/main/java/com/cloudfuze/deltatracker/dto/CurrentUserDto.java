package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CurrentUserDto {

    private String email;
    private String name;
    private boolean allowed;
    private AppUserRole role;
}
