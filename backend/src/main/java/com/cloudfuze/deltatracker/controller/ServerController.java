package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.AssignProjectRequest;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.UpdateServerRequest;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.service.ServerService;
import com.cloudfuze.deltatracker.service.WorkspacePairService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.http.HttpStatus;
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

    // Product type lives on the Server itself -- edited independently of adding a new server (e.g.
    // to set/correct it on a server created before this field existed).
    @PatchMapping("/{id}")
    public ServerReadinessDto update(@PathVariable Long id, @RequestBody UpdateServerRequest request) {
        return serverService.updateProductType(id, request.getProductType());
    }

    // combination is optional: when present, this is the "add a combination, then upload its CSV"
    // flow (WorkspacePairService.importCsvForServerCombination) -- the CSV carries no server_url or
    // combination column, both are already chosen in the UI. Absent, it's the existing plain
    // per-server import where combination (if any) comes from a CSV column per row.
    @PostMapping("/{id}/pairs/import")
    public WorkspacePairImportResultDto importPairs(@PathVariable Long id, @RequestParam MultipartFile file,
                                                      @RequestParam(required = false) String combination) {
        return combination != null
                ? workspacePairService.importCsvForServerCombination(id, combination, file)
                : workspacePairService.importCsv(id, file);
    }

    // Removes every migration pair under one combination for this server -- the "delete combination"
    // action on the project page. Distinct from project deletion: this only clears pairs, the
    // server itself and its other combinations are untouched.
    @DeleteMapping("/{id}/pairs")
    public void deletePairsByCombination(@PathVariable Long id, @RequestParam String combination) {
        workspacePairService.deleteByServerAndCombination(id, combination);
    }

    @PostMapping("/{id}/project")
    public ServerReadinessDto assignProject(@PathVariable Long id, @RequestBody AssignProjectRequest request) {
        return serverService.assignProject(id, request.getProjectId());
    }

    // Decommissioning is per-server: available once every combination under it has completed its Final
    // Delta. ADMIN-only, gated both in SecurityConfig and again in ServerService. This ERASES the
    // server and everything under it (see ServerService.decommission) -- 204 rather than a
    // ServerReadinessDto because there is no server left to describe, and there is no undo endpoint
    // for the same reason.
    @PostMapping("/{id}/decommission")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decommission(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        serverService.decommission(id, JwtEmailUtil.extractEmail(jwt));
    }

}
