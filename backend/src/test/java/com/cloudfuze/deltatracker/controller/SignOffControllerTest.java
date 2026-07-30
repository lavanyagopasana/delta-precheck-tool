package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.dto.SignOffApprovalDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.SignOffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for {@link SignOffController} with the real {@link SecurityConfig} active. The
 * sign-off endpoints are role-gated (ADMIN / MIGRATION_MANAGER / DEV_LEAD / QA_LEAD), so this proves
 * 401 for no token, 403 for an authenticated-but-wrong-role caller (MIGRATION_ENGINEER), and 200 for
 * an allowed role (DEV_LEAD).
 */
@WebMvcTest(SignOffController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class SignOffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private SignOffService signOffService;
    @MockBean private AppUserService appUserService;

    private static final String APPROVE_URL = "/api/servers/1/signoffs/DEV_LEAD/approve";

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        mockMvc.perform(post(APPROVE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(signOffService, never()).approve(anyLong(), any(), anyString(), any());
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        when(appUserService.roleOf("eng@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));

        mockMvc.perform(post(APPROVE_URL)
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(signOffService, never()).approve(anyLong(), any(), anyString(), any());
    }

    @Test
    void allowedRoleReaches200() throws Exception {
        when(appUserService.roleOf("dev@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.DEV_LEAD));
        when(signOffService.approve(anyLong(), any(), anyString(), any())).thenReturn(new SignOffApprovalDto());

        mockMvc.perform(post(APPROVE_URL)
                        .with(jwt().jwt(j -> j.claim("preferred_username", "dev@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qaRequired\":true}"))
                .andExpect(status().isOk());
        verify(signOffService).approve(anyLong(), any(), anyString(), any());
    }
}
