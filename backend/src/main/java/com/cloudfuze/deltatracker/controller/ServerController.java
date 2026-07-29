package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.AssignProjectRequest;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.service.ServerService;
import com.cloudfuze.deltatracker.service.WorkspacePairService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final WorkspacePairService workspacePairService;

    public ServerController(ServerService serverService, WorkspacePairService workspacePairService) {
        this.serverService = serverService;
        this.workspacePairService = workspacePairService;
    }

    @GetMapping
    public List<ServerReadinessDto> listAll() {
        return serverService.listReadiness();
    }

    @GetMapping("/{id}")
    public Server get(@PathVariable Long id) {
        return serverService.findOrThrow(id);
    }

    @GetMapping("/{id}/readiness")
    public ServerReadinessDto readiness(@PathVariable Long id) {
        return serverService.getReadiness(id);
    }

    @PostMapping("/{id}/pairs/import")
    public WorkspacePairImportResultDto importPairs(@PathVariable Long id, @RequestParam MultipartFile file) {
        return workspacePairService.importCsv(id, file);
    }

    @PostMapping("/{id}/project")
    public ServerReadinessDto assignProject(@PathVariable Long id, @RequestBody AssignProjectRequest request) {
        return serverService.assignProject(id, request.getProjectId());
    }

    // Post-Delta lifecycle, engineer-driven (gated in SecurityConfig to ADMIN + MIGRATION_ENGINEER).
    @PostMapping("/{id}/delta/start")
    public ServerReadinessDto startDelta(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return serverService.startDelta(id, JwtEmailUtil.extractEmail(jwt));
    }

    @PostMapping("/{id}/delta/finish")
    public ServerReadinessDto finishDelta(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return serverService.finishDelta(id, JwtEmailUtil.extractEmail(jwt));
    }
}
