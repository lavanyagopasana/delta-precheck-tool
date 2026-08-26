package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the project list out of the PMO tool (Neutara PM) at {@code GET /api/external/projects}.
 *
 * <p>Mirrors {@link TicketLookupService}: one {@code java.net.http.HttpClient}, env-driven base URL
 * and credential, and a blank credential meaning "feature disabled" with a readable message rather
 * than a raw connection error.
 *
 * <p>Things established against the live endpoint on 2026-08-26, worth knowing before editing:
 * <ul>
 *   <li><b>Do not use port 3001.</b> PMO's own documentation hands out
 *       {@code http://<server>:3001/api/external/projects}, but 3001 is its internal app port; from
 *       anywhere else it sits behind nginx on 443. The port form simply times out.</li>
 *   <li><b>Auth is one header, {@code X-API-Key}</b> - not a bearer token like the ticketing API. A
 *       wrong or absent key returns {@code 401 {"success":false,"error":"Invalid or missing API key"}}.</li>
 *   <li><b>A 503 here means PMO's own {@code EXTERNAL_API_KEY} is unset on their server</b>, not that
 *       our key is wrong - it short-circuits before authenticating, returning the identical 503 to a
 *       valid key, an invalid key and no key at all. That state held for the whole of setup day, so
 *       the message says so explicitly rather than letting somebody re-issue our key chasing it.</li>
 *   <li><b>The whole list arrives in one response</b> - {@code {"success":true,"total":190,"data":[...]}},
 *       about 100KB, and no pagination parameters are offered. {@code total} is cross-checked against
 *       the size of {@code data} below, because a feed that quietly starts paging would otherwise look
 *       like projects being deleted in PMO.</li>
 *   <li><b>Names are UTF-8 and 38 of them contain an em-dash</b> ({@code entera utilities — content}).
 *       {@code BodyHandlers.ofString()} honours the response's {@code charset=utf-8}, so this needs no
 *       special handling - but do not "simplify" it to a byte-array read with a default charset.</li>
 * </ul>
 */
@Service
public class PmoProjectClient {

    // Generous next to TicketLookupService's 8s: this is one ~100KB list covering every project in the
    // business, fetched on a background poll where slow beats failed.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper objectMapper;

    @Value("${pmo.base-url:}")
    private String baseUrl;

    @Value("${pmo.api-key:}")
    private String apiKey;

    public PmoProjectClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** False when either half of the config is blank, which disables the sync entirely. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    /**
     * Every project PMO reports, unfiltered. Status filtering is deliberately the caller's job so the
     * sync can report how many records it chose to leave out rather than hiding the difference.
     */
    public List<PmoProjectDto> fetchProjects() {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PMO sync isn't configured -- ask an admin to set PMO_BASE_URL and PMO_API_KEY.");
        }

        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/external/projects"))
                .timeout(REQUEST_TIMEOUT)
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the PMO tool right now -- try again shortly.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The PMO fetch was interrupted -- try again.");
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The PMO tool rejected our API key -- check PMO_API_KEY.");
        }
        if (response.statusCode() == 503) {
            // See the class comment: this is their configuration, not ours, and saying so here saves
            // the next person from assuming our key has gone stale.
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The PMO tool reports its external API is switched off (EXTERNAL_API_KEY unset on "
                            + "their server). Our key is fine -- this has to be fixed in the PMO deployment.");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The PMO tool responded with HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The PMO tool returned a response we couldn't read as JSON.");
        }

        JsonNode data = root.path("data");
        if (!data.isArray()) {
            // Tolerate a bare top-level array in case the envelope is ever dropped, but nothing more
            // speculative than that -- silently returning empty would read as "PMO has no projects".
            if (root.isArray()) {
                data = root;
            } else {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The PMO tool's response had no \"data\" array -- its API shape may have changed.");
            }
        }

        List<PmoProjectDto> projects = new ArrayList<>();
        for (JsonNode node : data) {
            PmoProjectDto dto = new PmoProjectDto();
            dto.setExternalId(text(node, "id"));
            dto.setName(text(node, "name"));
            dto.setCustomerName(text(node, "customerName"));
            dto.setManagerName(text(node, "projectManager"));
            dto.setStatus(text(node, "status"));
            dto.setPhase(text(node, "phase"));
            dto.setMigrationTypes(text(node, "migrationTypes"));
            projects.add(dto);
        }

        // The feed advertises its own count. If it ever exceeds what we actually received, the
        // single-response assumption above has broken and we are silently importing a subset --
        // which the sync would then read as "the missing ones no longer exist in PMO". Fail loudly.
        JsonNode total = root.path("total");
        if (total.isInt() && total.asInt() > projects.size()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "PMO reported " + total.asInt() + " projects but returned only " + projects.size()
                            + " -- it has started paginating and this integration needs updating.");
        }
        return projects;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText.isBlank() ? null : asText;
    }

    private static String shorten(String body) {
        if (body == null) {
            return "(empty body)";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
