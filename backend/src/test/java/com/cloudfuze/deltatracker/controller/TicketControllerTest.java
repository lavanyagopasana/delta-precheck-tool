package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.config.SecurityConfig;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.service.AppUserService;
import com.cloudfuze.deltatracker.service.TicketService;
import com.cloudfuze.deltatracker.service.UrlValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for {@link TicketController} with the real {@link SecurityConfig} active (auth
 * enabled via a non-blank azure.client-id). Covers 401 for an unauthenticated request, the
 * GlobalExceptionHandler JSON shape on a @Valid failure, and the happy 201.
 */
@WebMvcTest(TicketController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"azure.client-id=test-client", "azure.tenant-id="})
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private TicketService ticketService;
    @MockBean private UrlValidationService urlValidationService;
    @MockBean private AppUserService appUserService;

    private static final String VALID_BODY =
            "{\"serverId\":1,\"ticketUrl\":\"https://jira.example.com/browse/T-1\",\"createdBy\":\"eng@cloudfuze.com\",\"status\":\"OPEN\"}";

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verify(ticketService, never()).create(any());
    }

    @Test
    void invalidBodyReturns400WithErrorJsonShape() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/tickets")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        verify(ticketService, never()).create(any());
    }

    @Test
    void validRequestFromAllowedUserReturns201() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);
        when(ticketService.create(any())).thenReturn(new TicketDto());

        mockMvc.perform(post("/api/tickets")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
        verify(ticketService).create(any());
    }

    // Validation matrix (Part 2): ticketUrl over the 512 column length is rejected at bind with a
    // clear 400 in the GlobalExceptionHandler shape, not at insert.
    @Test
    void oversizeTicketUrlOnCreateReturns400() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);
        String longUrl = "https://jira.example.com/browse/" + "x".repeat(600);
        String body = "{\"serverId\":1,\"ticketUrl\":\"" + longUrl + "\",\"createdBy\":\"eng@cloudfuze.com\",\"status\":\"OPEN\"}";

        mockMvc.perform(post("/api/tickets")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("512 characters or fewer")));
        verify(ticketService, never()).create(any());
    }

    @Test
    void oversizeTicketUrlOnUpdateReturns400() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);
        String longUrl = "https://jira.example.com/browse/" + "x".repeat(600);
        String body = "{\"ticketUrl\":\"" + longUrl + "\",\"status\":\"OPEN\"}";

        mockMvc.perform(put("/api/tickets/1")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("512 characters or fewer")));
        verify(ticketService, never()).update(anyLong(), any(), anyString(), any());
    }

    // STEP 8: a stale-version write (two users acting on the same ticket) surfaces as 409 with the
    // reload message from GlobalExceptionHandler, not a generic 500 -- this is the contract the
    // frontend's apiErrorMessage() relies on to tell the user their view was stale.
    @Test
    void staleVersionUpdateReturns409WithReloadMessage() throws Exception {
        when(appUserService.isAllowed(anyString())).thenReturn(true);
        when(ticketService.resolve(anyLong(), anyString(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        com.cloudfuze.deltatracker.entity.Ticket.class, 1L));

        mockMvc.perform(patch("/api/tickets/1/resolve")
                        .with(jwt().jwt(j -> j.claim("preferred_username", "eng@cloudfuze.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("changed by someone else")));
    }
}
