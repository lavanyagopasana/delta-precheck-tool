package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.ProjectService;
import com.cloudfuze.deltatracker.service.ServerService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer validation tests (Part 2) for {@link ProjectController} with the real {@link SecurityConfig}.
 * Covers the Bean Validation rejections added this pass: blank name on create (@NotBlank), blank name
 * on update (@NotBlank -- was @NotNull, which let "" through), and an invalid engineer email in the
 * assignments list (element-level @Email). Each must return 400 in the GlobalExceptionHandler shape.
 */
@WebMvcTest(ProjectController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ProjectService projectService;
    @MockBean private AppUserService appUserService;
    @MockBean private ServerService serverService;
    // ProjectController gained the Metabase status endpoint; @WebMvcTest builds the real controller,
    // so every constructor dependency needs a bean or the context fails before any test runs.
    @MockBean private com.cloudfuze.deltatracker.service.MetabaseStatusService metabaseStatusService;

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.claim("preferred_username", "mm@cloudfuze.com")));
    }

    @Test
    void blankNameOnCreateReturns400() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);

        mockMvc.perform(asUser(post("/api/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
        verify(projectService, never()).create(any(), any(), any());
    }

    @Test
    void blankNameOnUpdateReturns400() throws Exception {
        // PATCH /api/projects/{id} is role-gated (ADMIN/MM/ENGINEER) -- authorize as a Migration Manager.
        when(appUserService.roleOf("mm@cloudfuze.com")).thenReturn(Optional.of(AppUserRole.MIGRATION_MANAGER));

        mockMvc.perform(asUser(patch("/api/projects/5"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
        verify(projectService, never()).updateDetails(any(), any(), any(), any());
    }

    @Test
    void invalidEngineerEmailInAssignmentsReturns400() throws Exception {
        // PATCH /api/projects/{id}/assignments falls through to allowlistRequired (two path segments).
        when(appUserService.isAllowed(anyString())).thenReturn(true);

        mockMvc.perform(asUser(patch("/api/projects/5/assignments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerEmails\":[\"not-an-email\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
        verify(projectService, never()).updateAssignments(any(), any());
    }
}
