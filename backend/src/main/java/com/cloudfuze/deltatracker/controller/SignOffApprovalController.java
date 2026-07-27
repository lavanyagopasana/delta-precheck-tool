package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.SignOffService;
import com.cloudfuze.deltatracker.util.JwtEmailUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/signoff-approvals")
public class SignOffApprovalController {

    private final SignOffService signOffService;
    private final AppUserService appUserService;

    public SignOffApprovalController(SignOffService signOffService, AppUserService appUserService) {
        this.signOffService = signOffService;
        this.appUserService = appUserService;
    }

    @GetMapping
    public List<SignOffApprovalDto> list(@AuthenticationPrincipal Jwt jwt) {
        String email = JwtEmailUtil.extractEmail(jwt);
        return signOffService.listApprovals(email, appUserService.roleOf(email).orElse(null));
    }
}
