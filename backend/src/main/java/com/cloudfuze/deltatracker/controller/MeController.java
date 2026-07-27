package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.CurrentUserDto;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final AppUserService appUserService;

    public MeController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @GetMapping("/api/me")
    public CurrentUserDto me(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);

        CurrentUserDto dto = new CurrentUserDto();
        dto.setEmail(email);
        dto.setName(jwt != null ? jwt.getClaimAsString("name") : null);
        dto.setAllowed(appUserService.isAllowed(email));
        appUserService.roleOf(email).ifPresent(dto::setRole);
        return dto;
    }
}
