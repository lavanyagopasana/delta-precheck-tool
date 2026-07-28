package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.ProjectAssignmentRequest;
import com.cloudfuze.deltatracker.dto.ProjectCreateRequest;
import com.cloudfuze.deltatracker.dto.ProjectDetailDto;
import com.cloudfuze.deltatracker.dto.ProjectSummaryDto;
import com.cloudfuze.deltatracker.dto.ProjectUpdateRequest;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.ProjectService;
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

    public ProjectController(ProjectService projectService, AppUserService appUserService) {
        this.projectService = projectService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<ProjectSummaryDto> list(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.list(email, appUserService.roleOf(email).orElse(null));
    }

    @GetMapping("/{id}")
    public ProjectDetailDto get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return projectService.getDetail(id, email, appUserService.roleOf(email).orElse(null));
    }

    @PostMapping
    public ProjectSummaryDto create(@Valid @RequestBody ProjectCreateRequest request) {
        return projectService.create(request.getName(), request.getProductType(), request.getCreatedBy(),
                request.getMigrationManagerName());
    }

    @PatchMapping("/{id}/assignments")
    public ProjectSummaryDto updateAssignments(@PathVariable Long id, @RequestBody ProjectAssignmentRequest request) {
        return projectService.updateAssignments(id, request);
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

    // Fine-grained authorization (creator / managing MM / admin, and the Delta-initiated audit
    // guard) lives in the service; SecurityConfig only gates the route to roles that can ever delete.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        projectService.delete(id, email, appUserService.roleOf(email).orElse(null));
    }
}
