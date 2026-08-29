package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.DashboardService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AppUserService appUserService;

    public DashboardController(DashboardService dashboardService, AppUserService appUserService) {
        this.dashboardService = dashboardService;
        this.appUserService = appUserService;
    }

    // Caller identity is extracted the same way every other read does it (ProjectController.list),
    // because the dashboard's figures are now scoped to what this person may see rather than being
    // a whole-database rollup.
    @GetMapping("/summary")
    public DashboardSummaryDto summary(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return dashboardService.getSummary(email, appUserService.roleOf(email).orElse(null));
    }
}
