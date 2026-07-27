package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.WorkspacePairService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/pairs")
public class WorkspacePairController {

    private final WorkspacePairService workspacePairService;
    private final AppUserService appUserService;

    public WorkspacePairController(WorkspacePairService workspacePairService, AppUserService appUserService) {
        this.workspacePairService = workspacePairService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<WorkspacePairDto> listByServer(@RequestParam Long serverId) {
        return workspacePairService.listByServer(serverId);
    }

    @GetMapping("/{id}")
    public WorkspacePairDto get(@PathVariable Long id) {
        return workspacePairService.get(id);
    }

    @PostMapping("/import")
    public WorkspacePairImportResultDto importGlobal(@RequestParam MultipartFile file,
                                                      @RequestParam(required = false) Long projectId,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return workspacePairService.importCsvGlobal(file, projectId, email, appUserService.isAdmin(email));
    }
}
