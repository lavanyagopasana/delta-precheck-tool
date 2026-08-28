package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PmoDeltaPhaseWebhookRequest;
import com.cloudfuze.deltatracker.service.PmoWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives PMO Tracker's Delta-phase webhook. Called by PMO's server directly, never a browser, so
 * there is no Azure AD token to check -- {@code SecurityConfig} permits this one route through
 * unauthenticated, and {@code X-API-Key} (verified in {@link PmoWebhookService}) is the only guard.
 *
 * <p>Thin by convention (see {@code .claude/rules/architecture-boundaries.md}): extract the header
 * and body, delegate, respond. required=false on the header so a call that omits it entirely reaches
 * {@code verifyApiKey} as a clean "missing" case instead of Spring's generic 400 for a missing
 * required header -- both end up 4xx, but this one is in {@code GlobalExceptionHandler}'s uniform
 * error shape.
 */
@RestController
@RequestMapping("/api/webhooks/pmo")
public class PmoWebhookController {

    private final PmoWebhookService pmoWebhookService;

    public PmoWebhookController(PmoWebhookService pmoWebhookService) {
        this.pmoWebhookService = pmoWebhookService;
    }

    @PostMapping("/delta-phase")
    public ResponseEntity<Void> deltaPhase(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                            @RequestBody PmoDeltaPhaseWebhookRequest request) {
        pmoWebhookService.verifyApiKey(apiKey);
        pmoWebhookService.handle(request);
        return ResponseEntity.ok().build();
    }
}
