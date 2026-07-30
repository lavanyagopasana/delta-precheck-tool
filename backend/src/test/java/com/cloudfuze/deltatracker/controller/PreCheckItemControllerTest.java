package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.PreCheckItemService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer validation test (Part 2) for {@link PreCheckItemController}. The update method now
 * carries @Valid and {@code PreCheckItemUpdateRequest.status} is @NotNull -- a body with no status
 * (previously an insert-time DataIntegrityViolation into a NOT NULL column) is now a clean 400 in
 * the GlobalExceptionHandler shape.
 */
@WebMvcTest(PreCheckItemController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class PreCheckItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private PreCheckItemService preCheckItemService;
    @MockBean private AppUserService appUserService;

    @Test
    void missingStatusOnItemUpdateReturns400() throws Exception {
        // POST on this path is role-gated (ADMIN/ENGINEER/MM) -- authorize as a Migration Engineer.
        when(appUserService.roleOf("eng@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_ENGINEER));

        mockMvc.perform(post("/api/servers/5/precheck-items/9")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"looks good\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("status")));
        verify(preCheckItemService, never()).update(anyLong(), anyLong(), any());
    }
}
