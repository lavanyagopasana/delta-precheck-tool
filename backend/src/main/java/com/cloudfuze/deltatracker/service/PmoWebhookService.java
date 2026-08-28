package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoDeltaPhaseWebhookRequest;
import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.dto.PmoSyncResultDto;
import com.cloudfuze.deltatracker.dto.PmoWebhookProjectDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Handles PMO Tracker's Delta-phase webhook -- the one way a project now arrives from PMO (see
 * {@code PmoSyncService}'s class javadoc). PMO calls {@code POST /api/webhooks/pmo/delta-phase} the
 * moment a project's phase moves to DELTA, presenting {@code X-API-Key} rather than an Azure AD
 * token: {@code SecurityConfig} permits this one route through unauthenticated, and this class is
 * what actually checks the caller is really PMO.
 *
 * <p>{@code pmo.webhook-api-key} is a SEPARATE property from {@code pmo.api-key}, even though PMO's
 * own team said they'd reuse the same value operationally: one is the key THIS app presents to PMO
 * (outbound, {@code PmoProjectClient}), the other is the key PMO must present to US (inbound, here).
 * Conflating them into one property would mean rotating either credential silently breaks or
 * re-authorizes the other side too.
 */
@Service
public class PmoWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PmoWebhookService.class);

    static final String DELTA_PHASE_EVENT = "PROJECT_PHASE_MOVED_TO_DELTA";

    private final PmoSyncService pmoSyncService;

    @Value("${pmo.webhook-api-key:}")
    private String expectedApiKey;

    public PmoWebhookService(PmoSyncService pmoSyncService) {
        this.pmoSyncService = pmoSyncService;
    }

    /** Throws if the caller didn't present the exact key configured for this webhook. */
    public void verifyApiKey(String presentedKey) {
        if (!StringUtils.hasText(expectedApiKey)) {
            // Never silently open the door: an unconfigured key must refuse everything, not admit
            // everyone -- the opposite failure mode from a misconfigured Azure client id (see
            // SecurityConfig), but the same principle: loud beats silent.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This webhook isn't configured yet -- pmo.webhook-api-key is unset.");
        }
        if (!StringUtils.hasText(presentedKey) || !expectedApiKey.equals(presentedKey)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid X-API-Key.");
        }
    }

    /**
     * Processes one Delta-phase notification. Only {@link #DELTA_PHASE_EVENT} is acted on -- any other
     * {@code event} value is accepted (2xx) but ignored, so PMO can add event types later without this
     * endpoint starting to reject calls it doesn't yet know how to handle.
     *
     * <p>{@code recentDeltaProjects} is deliberately never read here -- PMO's own spec for this payload
     * says it is context only (everyone else that reached Delta phase in the last 7 days), not a
     * second event to process.
     */
    public void handle(PmoDeltaPhaseWebhookRequest request) {
        if (!DELTA_PHASE_EVENT.equals(request.getEvent())) {
            log.info("PMO Delta-phase webhook: ignoring unrecognized event \"{}\".", request.getEvent());
            return;
        }
        PmoWebhookProjectDto webhookProject = request.getProject();
        if (webhookProject == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Webhook payload is missing \"project\".");
        }

        PmoSyncResultDto result = pmoSyncService.ingestOne(toPmoProjectDto(webhookProject));
        if (!result.getErrors().isEmpty()) {
            // Surfaced as a 500 (not swallowed as a quiet 200) so a failure actually shows up as failed
            // in PMO's own delivery log -- there is no retry on their side, and no reconciliation UI on
            // ours yet, so silently accepting a call that did nothing would lose the project entirely.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, String.join("; ", result.getErrors()));
        }
        log.info("PMO Delta-phase webhook: \"{}\" ({}) {}.", webhookProject.getName(), webhookProject.getId(),
                result.getCreatedCount() > 0 ? "created" : "updated");
    }

    private static PmoProjectDto toPmoProjectDto(PmoWebhookProjectDto webhookProject) {
        PmoProjectDto dto = new PmoProjectDto();
        dto.setExternalId(webhookProject.getId());
        dto.setName(webhookProject.getName());
        dto.setCustomerName(webhookProject.getCustomerName());
        dto.setManagerName(webhookProject.getProjectManager());
        dto.setStatus(webhookProject.getStatus());
        dto.setPhase(webhookProject.getPhase());
        dto.setMigrationTypes(webhookProject.getMigrationTypes());
        return dto;
    }
}
