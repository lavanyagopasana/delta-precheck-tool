package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.service.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer validation test (Part 2) for {@link AdminController}: an upsert body with a malformed
 * email is rejected at bind (@Email) with a 400 in the GlobalExceptionHandler shape, before the
 * value can become an EmailService recipient.
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private AppUserService appUserService;

    @Test
    void invalidEmailOnUpsertReturns400() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/admin/users")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "admin@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"role\":\"MIGRATION_ENGINEER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
        verify(appUserService, never()).upsert(anyString(), any(), anyString());
    }
}
