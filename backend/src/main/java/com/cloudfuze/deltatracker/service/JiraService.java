package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.JiraIssueDto;
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
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a single issue's summary/status/reporter/created-date from Jira's REST API, so "Log a
 * Ticket" only needs a ticket number OR a link to the ticket -- everything else Jira already knows
 * is pulled from there instead of being typed in by hand. This is a one-time snapshot taken at
 * logging time, not a live sync: if the Jira issue's status changes afterward, our copy doesn't
 * follow automatically (use the existing Resolve/Edit actions to update it here).
 *
 * <p>Deliberately mirrors {@link EmailService}'s "blank credential disables the feature" pattern --
 * leaving {@code jira.api-token} blank means the fetch fails with a clear "not configured" message
 * instead of a raw connection error, so this can ship before real Jira credentials exist.
 */
@Service
public class JiraService {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    // Jira's REST API renders "created" as e.g. "2026-08-01T09:12:34.000+0000" -- a numeric offset
    // with no colon, which java.time's built-in ISO_OFFSET_DATE_TIME formatter rejects.
    private static final DateTimeFormatter JIRA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    // Matches a Jira issue key (e.g. "PROJ-123") whether it's the entire input or embedded in a
    // pasted URL (e.g. ".../browse/PROJ-123" or "...?selectedIssue=PROJ-123") -- lets "Log a Ticket"
    // accept either shape through the same field instead of needing to know which one was typed.
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Za-z][A-Za-z0-9]*-[0-9]+)\\b");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper objectMapper;

    @Value("${jira.base-url:}")
    private String baseUrl;

    @Value("${jira.email:}")
    private String email;

    @Value("${jira.api-token:}")
    private String apiToken;

    public JiraService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JiraIssueDto fetchIssue(String ticketNumber) {
        String raw = ticketNumber == null ? "" : ticketNumber.trim();
        if (raw.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a ticket number or a link to it.");
        }
        // A pasted URL has the key embedded somewhere in it (path or query string) -- pull it out
        // rather than sending the whole URL as the "key" to Jira's issue-by-key endpoint, which would
        // just 404. A bare key matches this same pattern in full, so one extraction step covers both.
        Matcher matcher = JIRA_KEY.matcher(raw);
        String key = matcher.find() ? matcher.group(1).toUpperCase() : raw;
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(email) || !StringUtils.hasText(apiToken)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Jira integration isn't configured -- ask an admin to set JIRA_BASE_URL/JIRA_EMAIL/JIRA_API_TOKEN.");
        }

        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + "/rest/api/2/issue/" + encode(key) + "?fields=summary,status,reporter,created");

        String credentials = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + credentials)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Jira right now -- try again shortly.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Jira lookup was interrupted -- try again.");
        }

        if (response.statusCode() == 404) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No Jira ticket found for \"" + key + "\".");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Jira rejected our credentials -- check JIRA_EMAIL/JIRA_API_TOKEN.");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Jira responded with HTTP " + response.statusCode() + ".");
        }

        JsonNode fields;
        String issueKey;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            issueKey = root.path("key").asText(key);
            fields = root.path("fields");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Jira returned an unexpected response.");
        }

        JiraIssueDto dto = new JiraIssueDto();
        dto.setKey(issueKey);
        dto.setUrl(base + "/browse/" + issueKey);
        dto.setSummary(fields.path("summary").asText(null));
        dto.setResolved("done".equalsIgnoreCase(fields.path("status").path("statusCategory").path("key").asText("")));

        JsonNode reporter = fields.path("reporter");
        dto.setReporterDisplayName(reporter.path("displayName").asText(reporter.path("emailAddress").asText(null)));

        String created = fields.path("created").asText(null);
        if (created != null) {
            try {
                dto.setCreatedAt(OffsetDateTime.parse(created, JIRA_DATE_FORMAT).toLocalDateTime());
            } catch (Exception ignored) {
                // Leave createdAt null if Jira's date format ever changes shape -- not worth failing
                // the whole fetch over a display-only field.
            }
        }
        return dto;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
