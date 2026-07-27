package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.RosterDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RosterController {

    private final AppUserService appUserService;

    public RosterController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @GetMapping("/api/roster")
    public RosterDto roster() {
        RosterDto dto = new RosterDto();
        dto.setMigrationManagers(appUserService.emailsForRole(AppUserRole.MIGRATION_MANAGER));
        dto.setEngineers(appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER));
        return dto;
    }
}
