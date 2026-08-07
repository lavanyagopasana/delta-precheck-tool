package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.ApproveSignOffRequest;
import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.service.SignOffService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/combinations/{combinationId}/signoffs")
public class SignOffController {

    private final SignOffService signOffService;

    public SignOffController(SignOffService signOffService) {
        this.signOffService = signOffService;
    }

    @PostMapping("/{role}/approve")
    public SignOffApprovalDto approve(@PathVariable Long combinationId, @PathVariable SignOffRole role,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ApproveSignOffRequest request) {
        return signOffService.approve(combinationId, role, actorEmail(jwt, request), request.getQaRequired());
    }

    @PostMapping("/{role}/decline")
    public SignOffApprovalDto decline(@PathVariable Long combinationId, @PathVariable SignOffRole role,
                                       @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ApproveSignOffRequest request) {
        return signOffService.decline(combinationId, role, actorEmail(jwt, request), request.getReason());
    }

    // The JWT is the authoritative identity whenever auth is configured; the request body's email
    // is only used as a fallback in local/no-auth mode, where there's no token to extract it from.
    private String actorEmail(Jwt jwt, ApproveSignOffRequest request) {
        String fromJwt = JwtEmailUtil.extractEmail(jwt);
        return fromJwt != null ? fromJwt : request.getApproverEmail();
    }
}
