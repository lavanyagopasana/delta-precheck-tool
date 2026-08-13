package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.AppUserDto;
import com.cloudfuze.deltatracker.dto.AppUserImportResultDto;
import com.cloudfuze.deltatracker.dto.AppUserUpsertRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    private final AppUserService appUserService;

    public AdminController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<AppUserDto> list(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return appUserService.list();
    }

    @PostMapping
    public AppUserDto upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AppUserUpsertRequest request) {
        String adminEmail = requireAdmin(jwt);
        return appUserService.upsert(request.getEmail().trim().toLowerCase(), request.getRole(), adminEmail);
    }

    // `role` is the FALLBACK applied to rows whose own "role" column is blank or absent; it is
    // optional so a file that carries a role for every person needs no default at all. A row with
    // neither a role cell nor a default comes back as a per-row error.
    @PostMapping("/import-csv")
    public AppUserImportResultDto importCsv(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam MultipartFile file,
                                             @RequestParam(required = false) AppUserRole role) {
        String adminEmail = requireAdmin(jwt);
        return appUserService.importCsv(file, role, adminEmail);
    }

    @DeleteMapping("/{email}")
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String email) {
        String adminEmail = requireAdmin(jwt);
        appUserService.remove(email, adminEmail);
    }

    private String requireAdmin(Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        if (email == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can manage app access.");
        }
        appUserService.requireAdmin(email);
        return email;
    }
}
