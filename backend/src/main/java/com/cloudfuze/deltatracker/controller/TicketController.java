package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.TicketCreateRequest;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.dto.TicketUpdateRequest;
import com.cloudfuze.deltatracker.dto.UrlValidationResult;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.TicketService;
import com.cloudfuze.deltatracker.service.UrlValidationService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final UrlValidationService urlValidationService;
    private final AppUserService appUserService;

    public TicketController(TicketService ticketService, UrlValidationService urlValidationService,
                            AppUserService appUserService) {
        this.ticketService = ticketService;
        this.urlValidationService = urlValidationService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<TicketDto> listAll(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return ticketService.listAll(email, roleOf(email));
    }

    @GetMapping("/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", ticketService.countOpen());
    }

    // Server-side reachability check for a ticket link -- see UrlValidationService for why this can't
    // be done in the browser.
    @PostMapping("/validate-url")
    public UrlValidationResult validateUrl(@RequestBody Map<String, String> body) {
        return urlValidationService.validate(body.get("url"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDto create(@Valid @RequestBody TicketCreateRequest request) {
        return ticketService.create(request);
    }

    @PutMapping("/{id}")
    public TicketDto update(@PathVariable Long id, @Valid @RequestBody TicketUpdateRequest request,
                            @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return ticketService.update(id, request, email, roleOf(email));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        ticketService.delete(id, email, roleOf(email));
    }

    private AppUserRole roleOf(String email) {
        return appUserService.roleOf(email).orElse(null);
    }
}
