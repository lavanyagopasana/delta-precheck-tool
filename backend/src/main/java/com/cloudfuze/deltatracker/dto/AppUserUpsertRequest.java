package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.AppUserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AppUserUpsertRequest {

    @NotBlank
    private String email;

    @NotNull
    private AppUserRole role;
}
