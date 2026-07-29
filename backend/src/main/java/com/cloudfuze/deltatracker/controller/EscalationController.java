package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.EscalationCreateRequest;
import com.cloudfuze.deltatracker.dto.EscalationDto;
import com.cloudfuze.deltatracker.dto.EscalationResolveRequest;
import com.cloudfuze.deltatracker.dto.EscalationUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.EscalationService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/escalations")
public class EscalationController {

    private final EscalationService escalationService;
    private final AppUserService appUserService;

    public EscalationController(EscalationService escalationService, AppUserService appUserService) {
        this.escalationService = escalationService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<EscalationDto> listAll(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return escalationService.listAll(email, roleOf(email));
    }

    @GetMapping("/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", escalationService.countOpen());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EscalationDto create(@Valid @RequestBody EscalationCreateRequest request) {
        return escalationService.create(request);
    }

    @PatchMapping("/{id}/resolve")
    public EscalationDto resolve(@PathVariable Long id, @Valid @RequestBody EscalationResolveRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return escalationService.resolve(id, request, email, roleOf(email));
    }

    @PutMapping("/{id}")
    public EscalationDto update(@PathVariable Long id, @Valid @RequestBody EscalationUpdateRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return escalationService.update(id, request, email, roleOf(email));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        escalationService.delete(id, email, roleOf(email));
    }

    private AppUserRole roleOf(String email) {
        return appUserService.roleOf(email).orElse(null);
    }
}
