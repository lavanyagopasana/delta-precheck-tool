package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.ChangeLogEntryDto;
import com.cloudfuze.deltatracker.dto.CreateServerRequest;
import com.cloudfuze.deltatracker.dto.ProjectCreateRequest;
import com.cloudfuze.deltatracker.dto.ProjectDetailDto;
import com.cloudfuze.deltatracker.dto.MetabaseStatusDto;
import com.cloudfuze.deltatracker.dto.ProjectMetabaseRequest;
import com.cloudfuze.deltatracker.dto.ProjectSummaryDto;
import com.cloudfuze.deltatracker.dto.ProjectUpdateRequest;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.entity.ChangeLogEntityType;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.ChangeLogService;
import com.cloudfuze.deltatracker.service.MetabaseStatusService;
import com.cloudfuze.deltatracker.service.ProjectService;
import com.cloudfuze.deltatracker.service.ServerService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AppUserService appUserService;
    private final ServerService serverService;
    private final MetabaseStatusService metabaseStatusService;
    private final ChangeLogService changeLogService;

    public ProjectController(ProjectService projectService, AppUserService appUserService, ServerService serverService,
                              MetabaseStatusService metabaseStatusService, ChangeLogService changeLogService) {
        this.projectService = projectService;
        this.appUserService = appUserService;
        this.serverService = serverService;
        this.metabaseStatusService = metabaseStatusService;
        this.changeLogService = changeLogService;
    }

    @GetMapping
    public List<ProjectSummaryDto> list(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.list(email, appUserService.roleOf(email).orElse(null));
    }

    /**
     * Edit history, newest first. A GET open to any allowlisted caller -- the trail exists to be
     * read, so restricting it to the people allowed to make the edits would defeat the point.
     */
    @GetMapping("/{id}/history")
    public List<ChangeLogEntryDto> history(@PathVariable Long id) {
        return changeLogService.historyDtos(ChangeLogEntityType.PROJECT, id);
    }

    @GetMapping("/{id}")
    public ProjectDetailDto get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.getDetail(id, email, appUserService.roleOf(email).orElse(null));
    }

    @PostMapping
    public ProjectSummaryDto create(@Valid @RequestBody ProjectCreateRequest request) {
        return projectService.create(request.getName(), request.getCreatedBy(),
                request.getMigrationManagerName());
    }

    // Create a Server directly under this project (the "Server URL" add flow) -- no CSV needed.
    // SecurityConfig gates the route by role; the per-project check (must be this project's
    // Migration Manager or a team member, unless admin) mirrors WorkspacePairController.importGlobal
    // and lives in ServerService.createForProject.
    @PostMapping("/{id}/servers")
    public ServerReadinessDto createServer(@PathVariable Long id, @Valid @RequestBody CreateServerRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return serverService.createForProject(id, request.getName(), request.getProductType(), email, appUserService.isAdmin(email));
    }

    // Edit project details (name, product type, Migration Manager). Per-project permission
    // (admin / current MM / creator / assigned engineer) is enforced in the service; SecurityConfig
    // gates the route to the roles that can ever edit.
    @PatchMapping("/{id}")
    public ProjectSummaryDto updateDetails(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.updateDetails(id, request, email, appUserService.roleOf(email).orElse(null));
    }

    // Fix which Metabase database holds ONE PRODUCT TYPE's migration data for this project. Its own
    // route rather than a field on PATCH /api/projects/{id}, because that endpoint only lets a
    // non-admin edit a project with no servers yet -- and this is only useful once servers exist.
    // Permission (admin / this project's MM / an assigned engineer, and admin-only once fixed) is in
    // ProjectService.setMetabaseDatabase.
    @PatchMapping("/{id}/metabase")
    public ProjectSummaryDto setMetabaseDatabase(@PathVariable Long id,
                                                  @Valid @RequestBody ProjectMetabaseRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.setMetabaseDatabase(id, request, email,
                appUserService.roleOf(email).orElse(null));
    }

    // Remove one database from a product type. ADMIN only, enforced in the service -- adding widens
    // the figures visibly, removing shrinks them with nothing on screen to say a source was dropped.
    //
    // Query params rather than a body: DELETE with a request body is poorly supported by proxies and
    // by axios' own default config, and the two values are short identifiers.
    @DeleteMapping("/{id}/metabase")
    public ProjectSummaryDto removeMetabaseDatabase(@PathVariable Long id,
                                                     @RequestParam String productType,
                                                     @RequestParam String databaseName,
                                                     @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.removeMetabaseDatabase(id, productType, databaseName, email,
                appUserService.roleOf(email).orElse(null));
    }

    // The processStatus breakdown per product type, read live from Metabase. Not cached: it is fetched
    // only when somebody presses "Get process status", and a stale migration figure is worse than a
    // slow one -- this is what a Delta gets approved against.
    //
    // Read-only, so it is allowlist-gated in SecurityConfig rather than role-gated: the DEV_LEAD and
    // QA_LEAD approvers who may not CHOOSE the database are exactly the people who need to read it.
    @GetMapping("/{id}/metabase-status")
    public List<MetabaseStatusDto> metabaseStatus(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        // Visibility is enforced by loading the project through the same path the page uses -- a caller
        // who can't see the project gets its 404, not a status report about it.
        projectService.getDetail(id, email, appUserService.roleOf(email).orElse(null));
        return metabaseStatusService.statusForProject(id);
    }

    // Fine-grained authorization (creator / managing MM / admin, and the Delta-initiated audit
    // guard) lives in the service; SecurityConfig only gates the route to roles that can ever delete.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        projectService.delete(id, email, appUserService.roleOf(email).orElse(null));
    }
}
