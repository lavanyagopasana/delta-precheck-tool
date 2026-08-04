package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.CombinationReadinessDto;
import com.cloudfuze.deltatracker.service.WorkspaceCombinationService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/combinations")
public class WorkspaceCombinationController {

    private final WorkspaceCombinationService combinationService;

    public WorkspaceCombinationController(WorkspaceCombinationService combinationService) {
        this.combinationService = combinationService;
    }

    @GetMapping("/{id}")
    public CombinationReadinessDto get(@PathVariable Long id) {
        return combinationService.getReadiness(id);
    }

    // Post-Delta lifecycle (engineer-driven, gated in SecurityConfig to ADMIN + MIGRATION_ENGINEER),
    // same as ServerController's used to be -- just per-combination now.
    @PostMapping("/{id}/delta/start")
    public CombinationReadinessDto startDelta(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return combinationService.startDelta(id, JwtEmailUtil.extractEmail(jwt));
    }

    @PostMapping("/{id}/delta/finish")
    public CombinationReadinessDto finishDelta(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return combinationService.finishDelta(id, JwtEmailUtil.extractEmail(jwt));
    }
}
