package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.PreCheckSubmissionDto;
import com.cloudfuze.deltatracker.dto.SubmissionSubmitRequest;
import com.cloudfuze.deltatracker.service.PreCheckSubmissionService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/precheck-submission")
public class PreCheckSubmissionController {

    private final PreCheckSubmissionService submissionService;

    public PreCheckSubmissionController(PreCheckSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping
    public PreCheckSubmissionDto get(@PathVariable Long serverId,
                                      @RequestParam(required = false) String viewerEmail) {
        return submissionService.getByServer(serverId, viewerEmail);
    }

    @PostMapping("/submit")
    public PreCheckSubmissionDto submit(@PathVariable Long serverId,
                                         @Valid @RequestBody SubmissionSubmitRequest request) {
        return submissionService.submit(serverId, request);
    }

    // Un-submit a mistakenly-submitted pre-check so it can be corrected and resubmitted. Identity is
    // taken from the token (not the body) since this reverts a review request. Gated to
    // MIGRATION_ENGINEER/MIGRATION_MANAGER by SecurityConfig (same as the rest of precheck-submission).
    @PostMapping("/withdraw")
    public PreCheckSubmissionDto withdraw(@PathVariable Long serverId, @AuthenticationPrincipal Jwt jwt) {
        return submissionService.withdraw(serverId, JwtEmailUtil.extractEmail(jwt));
    }
}
