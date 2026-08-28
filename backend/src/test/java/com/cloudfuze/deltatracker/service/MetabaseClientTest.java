package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.MetabaseDatabaseDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link MetabaseClient} against a real loopback HTTP server, mirroring
 * {@link PmoProjectClientTest} -- the risk here is in the request/response handling and the two auth
 * modes, not in a seam worth mocking. {@code ReflectionTestUtils} sets the {@code @Value} fields.
 *
 * <p>The envelope below is Metabase's current {@code GET /api/database} shape; the bare-array case is
 * the older one this deliberately still tolerates.
 */
class MetabaseClientTest {

    private static final String TWO_DATABASES = """
            {"total":2,"data":[
              {"id":7,"name":"Zenith Corp Migration","engine":"mysql","is_sample":false},
              {"id":3,"name":"acme content","engine":"postgres","is_sample":false}]}
            """;

    private HttpServer server;
    private MetabaseClient client;

    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> apiKeyHeaders = new ArrayList<>();
    private final List<String> sessionHeaders = new ArrayList<>();
    // Per-path canned responses, so the session-login path and the database path can answer
    // differently within one test.
    private final Map<String, int[]> statusByPath = new LinkedHashMap<>();
    private final Map<String, String> bodyByPath = new LinkedHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();

        client = new MetabaseClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(client, "username", "");
        ReflectionTestUtils.setField(client, "password", "");

        stub("/api/database", 200, TWO_DATABASES);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Queue a status + body for a path. Several statuses may be queued to vary per call. */
    private void stub(String path, int status, String body) {
        statusByPath.put(path, new int[] {status});
        bodyByPath.put(path, body);
    }

    private void stubStatuses(String path, String body, int... statuses) {
        statusByPath.put(path, statuses);
        bodyByPath.put(path, body);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key"));
        sessionHeaders.add(exchange.getRequestHeaders().getFirst("X-Metabase-Session"));

        int[] statuses = statusByPath.getOrDefault(path, new int[] {404});
        // How many times this path has been hit so far, so a queue of statuses can be walked.
        long hits = requestedPaths.stream().filter(path::equals).count();
        int status = statuses[(int) Math.min(hits - 1, statuses.length - 1)];

        byte[] body = bodyByPath.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void sendsTheApiKeyHeaderAndReturnsDatabasesSortedByName() {
        List<MetabaseDatabaseDto> databases = client.fetchDatabases();

        assertThat(requestedPaths).containsExactly("/api/database");
        assertThat(apiKeyHeaders).containsExactly("test-api-key");
        // No session login when a key is configured -- that is the whole point of preferring it.
        assertThat(sessionHeaders).containsOnlyNulls();
        // Sorted case-insensitively, so "acme content" comes before "Zenith Corp Migration" even
        // though Metabase returned them the other way round.
        assertThat(databases).extracting(MetabaseDatabaseDto::getName)
                .containsExactly("acme content", "Zenith Corp Migration");
        assertThat(databases.get(0).getId()).isEqualTo(3L);
        assertThat(databases.get(0).getEngine()).isEqualTo("postgres");
    }

    @Test
    void toleratesTheOlderBareArrayShape() {
        stub("/api/database", 200, "[{\"id\":1,\"name\":\"legacy\",\"engine\":\"h2\"}]");

        assertThat(client.fetchDatabases()).extracting(MetabaseDatabaseDto::getName).containsExactly("legacy");
    }

    @Test
    void skipsEntriesWithNoName() {
        stub("/api/database", 200, "{\"data\":[{\"id\":1},{\"id\":2,\"name\":\"real\"}]}");

        assertThat(client.fetchDatabases()).extracting(MetabaseDatabaseDto::getName).containsExactly("real");
    }

    @Test
    void failsLoudlyOnAnUnrecognisedShapeRatherThanReturningEmpty() {
        // An empty list would render as "Metabase has no databases", which is a different and much
        // more misleading statement than "we couldn't read the response".
        stub("/api/database", 200, "{\"unexpected\":true}");

        assertThatThrownBy(() -> client.fetchDatabases())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("API shape may have changed");
    }

    @Test
    void reportsARejectedApiKeyAsSuch() {
        stub("/api/database", 401, "{\"message\":\"Unauthenticated\"}");

        assertThatThrownBy(() -> client.fetchDatabases())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("METABASE_API_KEY");
    }

    @Test
    void isNotConfiguredWithoutACredential() {
        ReflectionTestUtils.setField(client, "apiKey", "");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.fetchDatabases())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't configured");
    }

    @Test
    void logsInForASessionTokenWhenOnlyCredentialsAreSet() {
        useCredentialsInsteadOfApiKey();
        stub("/api/session", 200, "{\"id\":\"session-token-1\"}");

        client.fetchDatabases();

        assertThat(requestedPaths).containsExactly("/api/session", "/api/database");
        assertThat(sessionHeaders).containsExactly(null, "session-token-1");
    }

    @Test
    void reusesTheCachedSessionTokenAcrossCalls() {
        useCredentialsInsteadOfApiKey();
        stub("/api/session", 200, "{\"id\":\"session-token-1\"}");

        client.fetchDatabases();
        client.fetchDatabases();

        // One login for two fetches: Metabase sessions last 14 days, so logging in per request is a
        // needless round trip.
        assertThat(requestedPaths).containsExactly("/api/session", "/api/database", "/api/database");
    }

    @Test
    void reLogsInOnceWhenTheCachedSessionTokenHasBeenInvalidated() {
        useCredentialsInsteadOfApiKey();
        stubStatuses("/api/session", "{\"id\":\"session-token-1\"}", 200);
        // First database call 401s (token revoked server-side), second succeeds.
        stubStatuses("/api/database", TWO_DATABASES, 401, 200);

        assertThat(client.fetchDatabases()).hasSize(2);
        assertThat(requestedPaths).containsExactly("/api/session", "/api/database", "/api/session", "/api/database");
    }

    @Test
    void reportsRejectedCredentialsAsSuchRatherThanAsAKeyProblem() {
        useCredentialsInsteadOfApiKey();
        stub("/api/session", 401, "{\"errors\":{\"password\":\"did not match\"}}");

        assertThatThrownBy(() -> client.fetchDatabases())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("METABASE_USERNAME");
    }

    @Test
    void doesNotEchoTheBodyOfAFailedLogin() {
        useCredentialsInsteadOfApiKey();
        // A login response is the one place a session token could leak into an error message.
        stub("/api/session", 500, "{\"id\":\"leaked-token-should-not-appear\"}");

        assertThatThrownBy(() -> client.fetchDatabases())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("leaked-token-should-not-appear");
    }

    @Test
    void trimsTrailingSlashesOffTheBaseUrl() {
        ReflectionTestUtils.setField(client, "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "///");

        client.fetchDatabases();

        // Not "//api/database" -- Metabase 404s that.
        assertThat(requestedPaths).containsExactly("/api/database");
    }

    private void useCredentialsInsteadOfApiKey() {
        ReflectionTestUtils.setField(client, "apiKey", "");
        ReflectionTestUtils.setField(client, "username", "someone@cloudfuze.com");
        ReflectionTestUtils.setField(client, "password", "not-a-real-password");
    }
}
