package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.dto.PmoDeltaPhaseWebhookRequest;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.PmoWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This route is deliberately reachable with NO Azure AD token at all (see SecurityConfig's
 * permitAll matcher for it) -- PMO's server calls it directly. These tests confirm the route really
 * is open (no 401/403 from Spring Security itself) and that PmoWebhookService is what does the actual
 * authorization, via the X-API-Key header.
 */
@WebMvcTest(PmoWebhookController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class PmoWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PmoWebhookService pmoWebhookService;

    // SecurityConfig itself needs this bean whenever it's @Imported into a @WebMvcTest -- other
    // role-gated routes reference it even though this particular route never checks a role.
    @MockBean
    private AppUserService appUserService;

    private static final String BODY = """
            {"event":"PROJECT_PHASE_MOVED_TO_DELTA","project":{"id":"ext-1","name":"acme","customerName":"Acme",
            "projectManager":"Harika","status":"ACTIVE","phase":"DELTA","migrationTypes":"Gmail - Gmail"}}
            """;

    @Test
    void aValidKeyIsAcceptedWithNoAzureAdTokenAtAll() throws Exception {
        doNothing().when(pmoWebhookService).verifyApiKey("right-key");
        doNothing().when(pmoWebhookService).handle(any(PmoDeltaPhaseWebhookRequest.class));

        mockMvc.perform(post("/api/webhooks/pmo/delta-phase")
                        .header("X-API-Key", "right-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(pmoWebhookService).verifyApiKey("right-key");
        verify(pmoWebhookService).handle(any(PmoDeltaPhaseWebhookRequest.class));
    }

    @Test
    void aWrongKeyIsRejectedByTheServiceNotByBeingReachedAtAll() throws Exception {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-API-Key."))
                .when(pmoWebhookService).verifyApiKey("wrong-key");

        mockMvc.perform(post("/api/webhooks/pmo/delta-phase")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or invalid X-API-Key."));

        verify(pmoWebhookService, never()).handle(any());
    }

    @Test
    void aMissingKeyHeaderStillReachesTheServicesOwnCheck() throws Exception {
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-API-Key."))
                .when(pmoWebhookService).verifyApiKey(eq(null));

        mockMvc.perform(post("/api/webhooks/pmo/delta-phase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(pmoWebhookService, never()).handle(any());
    }
}
