package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public DashboardSummaryDto summary() {
        return dashboardService.getSummary();
    }
}
