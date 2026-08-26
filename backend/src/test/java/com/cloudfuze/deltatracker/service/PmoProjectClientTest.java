package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link PmoProjectClient} against a real loopback HTTP server, mirroring
 * {@link TicketLookupServiceTest} -- the risk in this class is in the request/response handling, not
 * in a seam worth mocking. {@code ReflectionTestUtils} sets the two {@code @Value} fields.
 *
 * <p>The JSON below is trimmed from a real {@code GET /api/external/projects} on 2026-08-26, envelope
 * and field names intact, including the em-dash that appears in 38 of the 190 live project names.
 */
class PmoProjectClientTest {

    private static final String ONE_PROJECT = """
            {"success":true,"total":1,"data":[
              {"id":"167b7a4a-bbb0-46e8-bf9f-efb2fadb53de","name":"entera utilities — content",
               "customerName":"Ryan","projectManager":"Harika","accountManager":"Deepak R J",
               "status":"ACTIVE","phase":"DELTA","phaseCompletionPct":0,"planType":"SILVER",
               "migrationTypes":"MyDrive - MyDrive, Shared Drive - Shared Drive",
               "plannedStart":"2026-07-07T00:00:00.000Z","plannedEnd":"2026-08-07T00:00:00.000Z",
               "actualStart":"2026-07-14T00:00:00.000Z","actualEnd":"2026-08-14T00:00:00.000Z",
               "delayStatus":"AT_RISK","delayDays":0,"updatedAt":"2026-08-25T17:23:43.494Z"}]}
            """;

    private HttpServer server;
    private PmoProjectClient client;
    private final List<String> requestedPaths = new ArrayList<>();
    private final List<String> apiKeyHeaders = new ArrayList<>();
    private int responseStatus = 200;
    private String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();

        client = new PmoProjectClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "apiKey", "test-api-key");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedPaths.add(exchange.getRequestURI().toString());
        apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        // charset matters: BodyHandlers.ofString() reads it off this header, and 38 live project names
        // contain a non-ASCII em-dash.
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void serve(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    @Test
    void readsTheLiveEnvelopeAndMapsEveryFieldWeUse() {
        serve(200, ONE_PROJECT);

        List<PmoProjectDto> projects = client.fetchProjects();

        assertThat(projects).singleElement().satisfies(p -> {
            assertThat(p.getExternalId()).isEqualTo("167b7a4a-bbb0-46e8-bf9f-efb2fadb53de");
            assertThat(p.getName()).isEqualTo("entera utilities — content");
            assertThat(p.getCustomerName()).isEqualTo("Ryan");
            assertThat(p.getManagerName()).isEqualTo("Harika");
            assertThat(p.getStatus()).isEqualTo("ACTIVE");
            assertThat(p.getPhase()).isEqualTo("DELTA");
            assertThat(p.getMigrationTypes()).isEqualTo("MyDrive - MyDrive, Shared Drive - Shared Drive");
        });
    }

    @Test
    void callsTheDocumentedPathAndSendsTheApiKeyHeader() {
        serve(200, ONE_PROJECT);

        client.fetchProjects();

        assertThat(requestedPaths).containsExactly("/api/external/projects");
        // X-API-Key, not Authorization: Bearer -- this API differs from the ticketing one.
        assertThat(apiKeyHeaders).containsExactly("test-api-key");
    }

    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotProduceADoubleSlashedPath() {
        ReflectionTestUtils.setField(client, "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        serve(200, ONE_PROJECT);

        client.fetchProjects();

        assertThat(requestedPaths).containsExactly("/api/external/projects");
    }

    @Test
    void blankConfigDisablesTheFetchWithAReadableMessage() {
        ReflectionTestUtils.setField(client, "apiKey", "");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PMO_BASE_URL")
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void aRejectedKeySaysToCheckOurKey() {
        serve(401, "{\"success\":false,\"error\":\"Invalid or missing API key\"}");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PMO_API_KEY");
    }

    @Test
    void a503SaysItIsTheirConfigNotOurKey() {
        // This exact state held for the whole of setup day: PMO's own EXTERNAL_API_KEY was unset, and
        // it returned the same 503 to a valid key, an invalid key and no key. The message must not send
        // somebody off to re-issue our key.
        serve(503, "{\"success\":false,\"error\":\"External API is not configured on this server "
                + "(EXTERNAL_API_KEY missing).\"}");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("EXTERNAL_API_KEY")
                .hasMessageContaining("Our key is fine");
    }

    @Test
    void aServerErrorQuotesTheBodySoTheirBugIsVisible() {
        // Their real 500 while the integration was being built: a missing DB column.
        serve(500, "{\"success\":false,\"error\":{\"message\":\"column \\\"phase_completion_pct\\\" does not exist\"}}");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("phase_completion_pct");
    }

    @Test
    void aResponseWithNoDataArrayFailsRatherThanReadingAsZeroProjects() {
        serve(200, "{\"success\":true,\"projects\":[]}");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no \"data\" array");
    }

    @Test
    void aBareTopLevelArrayIsStillAccepted() {
        serve(200, "[{\"id\":\"x\",\"name\":\"bare\",\"status\":\"ACTIVE\"}]");

        assertThat(client.fetchProjects()).singleElement()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("bare"));
    }

    @Test
    void aTotalHigherThanTheReturnedRowsFailsLoudlyInsteadOfImportingASubset() {
        // If PMO ever starts paginating, a silent subset would look to the sync like the missing
        // projects had been deleted over there.
        serve(200, "{\"success\":true,\"total\":190,\"data\":[{\"id\":\"x\",\"name\":\"only one\",\"status\":\"ACTIVE\"}]}");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("started paginating");
    }

    @Test
    void blankAndMissingFieldsBecomeNullRatherThanEmptyStrings() {
        serve(200, "{\"success\":true,\"total\":1,\"data\":[{\"id\":\"x\",\"name\":\"n\","
                + "\"customerName\":\"\",\"projectManager\":null,\"status\":\"ACTIVE\"}]}");

        assertThat(client.fetchProjects()).singleElement().satisfies(p -> {
            assertThat(p.getCustomerName()).isNull();
            assertThat(p.getManagerName()).isNull();
            assertThat(p.getPhase()).isNull();
        });
    }

    @Test
    void unparseableJsonIsReportedAsSuch() {
        serve(200, "not json at all");

        assertThatThrownBy(() -> client.fetchProjects())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("couldn't read as JSON");
    }
}
