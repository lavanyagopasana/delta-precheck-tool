package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ExternalTicketDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link TicketLookupService}'s translation of the Neutara Ticketing API's JSON into
 * {@link ExternalTicketDto}. This is the class the Jira -> Neutara swap actually rewrote, so the
 * response-shape assumptions are what's worth pinning down: they were derived from the live API on
 * 2026-08-18 and the fixtures below are trimmed copies of real responses.
 *
 * <p>Runs against a real loopback {@link HttpServer} rather than a mocked HttpClient, because the
 * service builds its own client internally -- and because the bugs worth catching here (a status
 * category read from the wrong nesting level, a timestamp format that won't parse) live in genuine
 * request/response handling, not in the seam. {@code ReflectionTestUtils} sets the two {@code @Value}
 * fields that Spring would normally inject.
 */
class TicketLookupServiceTest {

    // Trimmed from a real GET /api/issues/L1BOAR-15335. The nesting is the part under test: the
    // resolution signal is status.category, and reporter is an object, not a string.
    private static final String ISSUE_JSON = """
            {
              "id": "pg_202ntz5b7z",
              "key": "L1BOAR-15335",
              "cfKey": "CF-29519",
              "summary": "Auto Initializer for kohl servers",
              "status": {"id": "status_qa_inprogress", "name": "In Progress", "category": "%s"},
              "reporter": {"email": "bhagyashri.deokar@cloudfuze.com", "displayName": "%s"},
              "resolvedAt": null,
              "createdAt": "2026-08-18T13:16:00.604Z"
            }
            """;

    private HttpServer server;
    private TicketLookupService service;
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private int responseStatus = 200;
    private String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();

        service = new TicketLookupService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "apiToken", "nta_test_token");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().toString());
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void serveIssue(String statusCategory, String reporterDisplayName) {
        responseStatus = 200;
        responseBody = ISSUE_JSON.formatted(statusCategory, reporterDisplayName);
    }

    @Test
    @DisplayName("maps every field off a real issue response, and authenticates with a bearer token")
    void mapsIssueResponse() {
        serveIssue("in-progress", "Bhagyashri Deokar");

        ExternalTicketDto dto = service.fetchIssue("L1BOAR-15335");

        assertThat(dto.getKey()).isEqualTo("L1BOAR-15335");
        assertThat(dto.getSummary()).isEqualTo("Auto Initializer for kohl servers");
        assertThat(dto.getReporterDisplayName()).isEqualTo("Bhagyashri Deokar");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 13, 16, 0, 604_000_000));
        assertThat(dto.isResolved()).isFalse();
        // Not Jira's /rest/api/2/issue/... and not Basic auth -- both changed in the swap.
        assertThat(requestedPaths).containsExactly("/api/issues/L1BOAR-15335");
        assertThat(authHeaders).containsExactly("Bearer nta_test_token");
    }

    @Test
    @DisplayName("the canonical browse link is built from the API's own key, not the typed-in one")
    void buildsUrlFromCanonicalKey() {
        serveIssue("done", "Bhagyashri Deokar");

        // Looking up by the short "CF-..." alias returns the canonical "L1BOAR-15335"; the stored link
        // has to use the canonical key or it won't resolve for anyone who clicks it.
        ExternalTicketDto dto = service.fetchIssue("CF-29519");

        assertThat(requestedPaths).containsExactly("/api/issues/CF-29519");
        assertThat(dto.getKey()).isEqualTo("L1BOAR-15335");
        assertThat(dto.getUrl()).endsWith("/issues/L1BOAR-15335");
    }

    @Test
    @DisplayName("only status.category == done counts as resolved -- resolvedAt is never populated")
    void resolvesOnStatusCategoryOnly() {
        // The live API returned resolvedAt: null on all 50 sampled issues, including all 35 in a done
        // status. Reading resolution off resolvedAt would leave every resolved ticket OPEN forever.
        serveIssue("done", "Someone");
        assertThat(service.fetchIssue("L1BOAR-15335").isResolved()).isTrue();

        // The API is inconsistent about the separator on this one value ("in-progress" on most issues,
        // "in_progress" on others); neither is done, which is all that matters here.
        for (String notDone : List.of("in-progress", "in_progress", "todo", "")) {
            serveIssue(notDone, "Someone");
            assertThat(service.fetchIssue("L1BOAR-15335").isResolved())
                    .as("category=%s", notDone)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("extracts the key from a pasted ticket URL so the same field takes either shape")
    void extractsKeyFromPastedUrl() {
        serveIssue("todo", "Someone");

        service.fetchIssue("  https://neutaraticketing.cftools.live/issues/l1boar-15335  ");

        // Trimmed, key pulled out of the path, and upper-cased to match the tracker's own casing.
        assertThat(requestedPaths).containsExactly("/api/issues/L1BOAR-15335");
    }

    @Test
    @DisplayName("falls back to the reporter's email when the tracker has no display name")
    void fallsBackToReporterEmail() {
        responseStatus = 200;
        responseBody = """
                {"key":"L1BOAR-1","summary":"s","status":{"category":"todo"},
                 "reporter":{"email":"nobody@cloudfuze.com"},"createdAt":"2026-08-18T13:16:00.604Z"}
                """;

        assertThat(service.fetchIssue("L1BOAR-1").getReporterDisplayName()).isEqualTo("nobody@cloudfuze.com");
    }

    @Test
    @DisplayName("a malformed createdAt doesn't fail the whole fetch -- it's display-only")
    void toleratesUnparseableCreatedAt() {
        responseStatus = 200;
        responseBody = """
                {"key":"L1BOAR-1","summary":"s","status":{"category":"done"},
                 "reporter":{"displayName":"Someone"},"createdAt":"18/08/2026 13:16"}
                """;

        ExternalTicketDto dto = service.fetchIssue("L1BOAR-1");

        assertThat(dto.getCreatedAt()).isNull();
        assertThat(dto.getSummary()).isEqualTo("s");
        assertThat(dto.isResolved()).isTrue();
    }

    @Test
    @DisplayName("an unknown key is a 404, not a bad gateway")
    void unknownKeyIsNotFound() {
        responseStatus = 404;
        responseBody = "{\"error\":\"Issue not found\"}";

        assertThatThrownBy(() -> service.fetchIssue("NOPE-99999"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("NOPE-99999")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a rejected token names the setting to fix rather than leaking the token")
    void rejectedTokenPointsAtTheSetting() {
        responseStatus = 401;
        responseBody = "{\"error\":\"Unauthorized\"}";

        assertThatThrownBy(() -> service.fetchIssue("L1BOAR-15335"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("TICKETING_API_TOKEN")
                .hasMessageNotContaining("nta_test_token");
    }

    @Test
    @DisplayName("a blank token disables the feature with a clear message instead of a connection error")
    void blankTokenIsUnavailableNotAnError() {
        ReflectionTestUtils.setField(service, "apiToken", "");

        assertThatThrownBy(() -> service.fetchIssue("L1BOAR-15335"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't configured")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    @DisplayName("an empty ticket number is rejected before any network call")
    void blankInputIsRejected() {
        assertThatThrownBy(() -> service.fetchIssue("   "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    @DisplayName("a trailing slash on the configured base URL doesn't produce a doubled path")
    void toleratesTrailingSlashOnBaseUrl() {
        // The value handed over for this integration was "https://neutaraticketing.cftools.live/",
        // with the slash -- so this is the shape a real deployment is likely to be configured with.
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        serveIssue("done", "Someone");

        ExternalTicketDto dto = service.fetchIssue("L1BOAR-15335");

        assertThat(requestedPaths).containsExactly("/api/issues/L1BOAR-15335");
        assertThat(dto.getUrl()).doesNotContain("//issues");
    }
}
