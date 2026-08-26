package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.TeamAssignRequest;
import com.cloudfuze.deltatracker.dto.TeamDto;
import com.cloudfuze.deltatracker.dto.TeamRequest;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.TeamService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Team administration. Reads are open to any allowlisted caller (the project dashboard needs to
 * know team membership to filter its engineer picker); every write requires ADMIN.
 *
 * <p>requireAdmin here mirrors AdminController's -- a defence-in-depth check next to the
 * SecurityConfig matcher, not a replacement for it.
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;
    private final AppUserService appUserService;

    public TeamController(TeamService teamService, AppUserService appUserService) {
        this.teamService = teamService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<TeamDto> list() {
        return teamService.list();
    }

    @PostMapping
    public TeamDto create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamRequest request) {
        String adminEmail = requireAdmin(jwt);
        return teamService.create(request.getName(), adminEmail);
    }

    @PatchMapping("/{id}")
    public TeamDto rename(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                           @Valid @RequestBody TeamRequest request) {
        requireAdmin(jwt);
        return teamService.rename(id, request.getName());
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        requireAdmin(jwt);
        teamService.delete(id);
    }

    @PostMapping("/assign")
    public void assign(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TeamAssignRequest request) {
        requireAdmin(jwt);
        teamService.assign(request.getEmail().trim(), request.getTeamId());
    }

    private String requireAdmin(Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        if (email == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can manage teams.");
        }
        appUserService.requireAdmin(email);
        return email;
    }
}
