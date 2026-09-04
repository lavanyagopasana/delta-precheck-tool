package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.RosterDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.TeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RosterController {

    private final AppUserService appUserService;
    private final TeamService teamService;

    public RosterController(AppUserService appUserService, TeamService teamService) {
        this.appUserService = appUserService;
        this.teamService = teamService;
    }

    @GetMapping("/api/roster")
    public RosterDto roster() {
        RosterDto dto = new RosterDto();
        // Not emailsForRole(MIGRATION_MANAGER): an admin who also runs engagements is assignable
        // once flagged, and this list is what the project manager picker renders.
        dto.setMigrationManagers(appUserService.managerCandidateEmails());
        dto.setEngineers(appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER));
        dto.setDevLeads(appUserService.emailsForRole(AppUserRole.DEV_LEAD));
        dto.setQaLeads(appUserService.emailsForRole(AppUserRole.QA_LEAD));
        dto.setEngineersByManager(teamService.engineersByManager());
        return dto;
    }
}
