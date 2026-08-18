package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ExternalTicketDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a single issue's summary/status/reporter/created-date from the Neutara Technologies
 * Ticketing API, so "Log a Ticket" only needs a ticket number OR a link to the ticket -- everything
 * else the tracker already knows is pulled from there instead of being typed in by hand. This is a
 * one-time snapshot taken at logging time, not a live sync: if the issue's status changes afterward,
 * our copy doesn't follow automatically until {@code TicketService.syncOpenTicketsFromTracker}'s
 * scheduled poll notices (or somebody uses the Resolve/Edit actions here).
 *
 * <p>Replaced a Jira Cloud implementation on 2026-08-18. The tracker is deliberately Jira-shaped --
 * same {@code ABC-123} key format, same three-bucket status model -- so the swap was contained to
 * this class. What actually differs, and is worth knowing before editing:
 * <ul>
 *   <li><b>Auth is a single bearer token</b>, not Jira's {@code email:api-token} HTTP Basic pair. The
 *       token identifies the acting user by itself ({@code GET /api/auth/me} resolves it to a
 *       person), so there is no email to configure -- {@code JIRA_EMAIL} had no successor and was
 *       dropped rather than kept as a field nothing reads.</li>
 *   <li><b>Resolution is read off {@code status.category}, never {@code resolvedAt}.</b> Every one of
 *       the 50 issues sampled from the live API had {@code resolvedAt: null} -- including all 35
 *       sitting in a {@code done} status. That field is present but not populated, so trusting it
 *       would have left every resolved ticket showing as OPEN here forever.</li>
 *   <li><b>Timestamps are plain ISO-8601 UTC</b> ({@code 2026-08-18T13:16:00.604Z}), which
 *       {@code OffsetDateTime.parse} handles directly -- Jira's colon-less numeric offset needed a
 *       hand-built formatter, this doesn't.</li>
 * </ul>
 *
 * <p>Mirrors {@link EmailService}'s "blank credential disables the feature" pattern -- leaving
 * {@code ticketing.api-token} blank means the fetch fails with a clear "not configured" message
 * instead of a raw connection error.
 */
@Service
public class TicketLookupService {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    // Matches an issue key (e.g. "L1BOAR-15335") whether it's the entire input or embedded in a
    // pasted URL (e.g. ".../issues/L1BOAR-15335") -- lets "Log a Ticket" accept either shape through
    // the same field instead of needing to know which one was typed. Also matches the tracker's
    // shorter "CF-29519" alias key, which its issue endpoint resolves just as happily as the
    // canonical one (we then store whichever key the API echoes back, so the alias doesn't leak into
    // the saved link).
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Za-z][A-Za-z0-9]*-[0-9]+)\\b");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper objectMapper;

    @Value("${ticketing.base-url:}")
    private String baseUrl;

    @Value("${ticketing.api-token:}")
    private String apiToken;

    public TicketLookupService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExternalTicketDto fetchIssue(String ticketNumber) {
        String raw = ticketNumber == null ? "" : ticketNumber.trim();
        if (raw.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a ticket number or a link to it.");
        }
        // A pasted URL has the key embedded somewhere in it (path or query string) -- pull it out
        // rather than sending the whole URL as the "key" to the issue-by-key endpoint, which would
        // just 404. A bare key matches this same pattern in full, so one extraction step covers both.
        Matcher matcher = ISSUE_KEY.matcher(raw);
        String key = matcher.find() ? matcher.group(1).toUpperCase() : raw;
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiToken)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Ticketing integration isn't configured -- ask an admin to set TICKETING_BASE_URL/TICKETING_API_TOKEN.");
        }

        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + "/api/issues/" + encode(key));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the ticketing system right now -- try again shortly.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Ticket lookup was interrupted -- try again.");
        }

        if (response.statusCode() == 404) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No ticket found for \"" + key + "\".");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The ticketing system rejected our credentials -- check TICKETING_API_TOKEN.");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The ticketing system responded with HTTP " + response.statusCode() + ".");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The ticketing system returned an unexpected response.");
        }

        // Trust the API's own "key" over whatever was typed: looking up by the "CF-29519" alias
        // returns the canonical "L1BOAR-15335", and it's the canonical one the browse link needs.
        String issueKey = root.path("key").asText(key);

        ExternalTicketDto dto = new ExternalTicketDto();
        dto.setKey(issueKey);
        dto.setUrl(base + "/issues/" + issueKey);
        dto.setSummary(root.path("summary").asText(null));
        // See the class comment: status.category is the only reliable resolution signal here.
        dto.setResolved("done".equalsIgnoreCase(root.path("status").path("category").asText("")));

        JsonNode reporter = root.path("reporter");
        dto.setReporterDisplayName(reporter.path("displayName").asText(reporter.path("email").asText(null)));

        String created = root.path("createdAt").asText(null);
        if (created != null) {
            try {
                dto.setCreatedAt(OffsetDateTime.parse(created).toLocalDateTime());
            } catch (Exception ignored) {
                // Leave createdAt null if the date format ever changes shape -- not worth failing the
                // whole fetch over a display-only field.
            }
        }
        return dto;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
