package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.ChangeLogEntryDto;
import com.cloudfuze.deltatracker.dto.CombinationReadinessDto;
import com.cloudfuze.deltatracker.dto.DeltaCycleDto;
import com.cloudfuze.deltatracker.entity.ChangeLogEntityType;
import com.cloudfuze.deltatracker.service.ChangeLogService;
import com.cloudfuze.deltatracker.service.DeltaCycleService;
import com.cloudfuze.deltatracker.service.WorkspaceCombinationService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/combinations")
public class WorkspaceCombinationController {

    private final WorkspaceCombinationService combinationService;
    private final DeltaCycleService deltaCycleService;
    private final ChangeLogService changeLogService;

    public WorkspaceCombinationController(WorkspaceCombinationService combinationService,
                                           DeltaCycleService deltaCycleService,
                                           ChangeLogService changeLogService) {
        this.combinationService = combinationService;
        this.deltaCycleService = deltaCycleService;
        this.changeLogService = changeLogService;
    }

    @GetMapping("/{id}")
    public CombinationReadinessDto get(@PathVariable Long id) {
        return combinationService.getReadiness(id);
    }

    // This combination's Delta history -- one entry per completed cycle, each carrying the checklist
    // and sign-off snapshot frozen at its approval time. Read-only by nature (a snapshot is never
    // edited), so it needs no role beyond the allowlist default in SecurityConfig.
    /**
     * This combination's edit history, newest first.
     *
     * <p>Nothing edits a combination's details today -- there is no PATCH route for one -- so this is
     * empty by design rather than by omission, and exists so it works the day editing lands. A
     * combination's actual lifecycle (delta started, finished, declined) is the Delta History below.
     */
    @GetMapping("/{id}/history")
    public List<ChangeLogEntryDto> history(@PathVariable Long id) {
        return changeLogService.historyDtos(ChangeLogEntityType.COMBINATION, id);
    }

    @GetMapping("/{id}/delta-cycles")
    public List<DeltaCycleDto> deltaCycles(@PathVariable Long id) {
        return deltaCycleService.history(id);
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
