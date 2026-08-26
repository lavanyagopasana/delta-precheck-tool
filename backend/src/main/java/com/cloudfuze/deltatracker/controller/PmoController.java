package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PmoSyncResultDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.PmoSyncService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the PMO project sync, so an admin who has just created a project over there can
 * pull it in immediately instead of waiting for the five-minute poll.
 *
 * <p>Thin by convention (see {@code .claude/rules/architecture-boundaries.md}): extract the caller,
 * check they are an admin, delegate. {@code SecurityConfig} gates the route to ADMIN as well -- this
 * repeats the check as defence in depth, matching {@code AdminController} and {@code TeamController}.
 */
@RestController
@RequestMapping("/api/pmo")
public class PmoController {

    private final PmoSyncService pmoSyncService;
    private final AppUserService appUserService;

    public PmoController(PmoSyncService pmoSyncService, AppUserService appUserService) {
        this.pmoSyncService = pmoSyncService;
        this.appUserService = appUserService;
    }

    @PostMapping("/sync")
    public PmoSyncResultDto sync(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return pmoSyncService.sync();
    }

    private void requireAdmin(Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        if (email == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to sync projects from PMO.");
        }
        appUserService.requireAdmin(email);
    }
}
