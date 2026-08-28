package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.MetabaseDatabaseDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the database list out of Metabase ({@code GET /api/database}) so the project page can offer
 * the real names in a dropdown instead of asking somebody to retype one by hand.
 *
 * <p>Mirrors {@link PmoProjectClient}: one {@code java.net.http.HttpClient}, env-driven base URL and
 * credential, and a blank credential meaning "not configured" with a readable message rather than a
 * raw connection error.
 *
 * <p><b>Two auth modes, API key preferred.</b> Metabase accepts either an API key
 * ({@code x-api-key}, Metabase 0.49+, created under Admin &gt; Authentication &gt; API keys) or a
 * session token ({@code X-Metabase-Session}) obtained by posting credentials to
 * {@code /api/session}. The key is preferred because it is scoped, revocable, and doesn't put a
 * human's password in the environment. The username/password path exists only because API keys are an
 * admin-enabled feature and may not be switched on for this instance; if both are set the key wins.
 *
 * <p><b>Session tokens are cached in memory and re-fetched on a 401.</b> Metabase's default session
 * lifetime is 14 days, so logging in per request would be a needless round trip, but the token can
 * also be invalidated server-side at any time - hence the retry-once-on-401 rather than trusting the
 * cache. The token is never logged; see {@code .claude/rules/security-rules.md}.
 *
 * <p><b>Response shape.</b> Current Metabase returns {@code {"data":[...],"total":n}}; older versions
 * returned a bare array. Both are handled, because an unrecognised shape returning an empty list would
 * read on screen as "Metabase has no databases" rather than as a broken integration.
 */
@Service
public class MetabaseClient {

    private static final Logger log = LoggerFactory.getLogger(MetabaseClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper objectMapper;

    @Value("${metabase.base-url:}")
    private String baseUrl;

    @Value("${metabase.api-key:}")
    private String apiKey;

    @Value("${metabase.username:}")
    private String username;

    @Value("${metabase.password:}")
    private String password;

    // Cached session token for the username/password path. volatile because the scheduled/HTTP
    // threads that call this are not the same thread every time.
    private volatile String sessionToken;

    public MetabaseClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** True when there is a base URL and at least one usable credential. */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl)
                && (StringUtils.hasText(apiKey)
                    || (StringUtils.hasText(username) && StringUtils.hasText(password)));
    }

    /**
     * Every database Metabase can see, sorted by name so the dropdown is stable between loads
     * (Metabase returns them in its own internal order, which shifts as databases are re-synced).
     */
    public List<MetabaseDatabaseDto> fetchDatabases() {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Metabase isn't configured -- ask an admin to set METABASE_BASE_URL and either "
                            + "METABASE_API_KEY or METABASE_USERNAME/METABASE_PASSWORD.");
        }

        HttpResponse<String> response = sendAuthenticated(base() + "/api/database");
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, StringUtils.hasText(apiKey)
                    ? "Metabase rejected our API key -- check METABASE_API_KEY (it may have been revoked)."
                    : "Metabase rejected our credentials -- check METABASE_USERNAME and METABASE_PASSWORD.");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Metabase responded with HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Metabase returned a response we couldn't read as JSON.");
        }

        JsonNode data = root.path("data");
        if (!data.isArray()) {
            if (root.isArray()) {
                data = root; // older Metabase: bare top-level array
            } else {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Metabase's database list had no \"data\" array -- its API shape may have changed.");
            }
        }

        List<MetabaseDatabaseDto> databases = new ArrayList<>();
        for (JsonNode node : data) {
            String name = text(node, "name");
            if (name == null) {
                continue; // nothing to show in a dropdown, and nothing a project could store
            }
            MetabaseDatabaseDto dto = new MetabaseDatabaseDto();
            dto.setId(node.path("id").isNumber() ? node.path("id").asLong() : null);
            dto.setName(name);
            dto.setEngine(text(node, "engine"));
            databases.add(dto);
        }
        databases.sort(Comparator.comparing(MetabaseDatabaseDto::getName, String.CASE_INSENSITIVE_ORDER));
        return databases;
    }

    /**
     * Counts workspaces by {@code processStatus} in one collection, excluding rows owned by a
     * {@code @cloudfuze.com} address.
     *
     * <p>This is the Metabase UI flow "filter OwnerEmailId, Summarize > Count, Group by ProcessStatus"
     * expressed as an aggregation pipeline. Verified 2026-08-27 to reproduce that screen exactly
     * (8 rows, 6987 total, on db 195 / MessageWorkSpace).
     *
     * <p><b>The owner filter excludes the DOMAIN, not the word.</b> {@code @cloudfuze.com} owners are
     * CloudFuze staff running internal or test migrations -- on one real database that was 53
     * workspaces carrying 47 conflicts, which would otherwise inflate the customer's numbers. But
     * {@code cloudfuze@azaleawang.com} is a CloudFuze-operated account ON THE CUSTOMER'S domain and its
     * rows are genuine customer data, so matching the word "cloudfuze" anywhere would wrongly drop a
     * whole project's figures.
     *
     * <p>Returns raw status strings, never an enum: the vocabulary differs per product type (email says
     * {@code PROCESSED_WITH_CONFLICTS} and {@code PAUSE} where message says
     * {@code PROCESSED_WITH_SOME_CONFLICTS} and {@code SUSPENDED}), and an unrecognised value must reach
     * the screen rather than being dropped.
     *
     * @return status -> count, plus the {@code null} key for documents with no processStatus at all
     */
    public Map<String, Long> countByProcessStatus(long databaseId, String collection) {
        String excludeInternal = """
                [{"$match": {"ownerEmailId": {"$not": {"$regex": "@cloudfuze\\\\.com$", "$options": "i"}}}},
                 {"$group": {"_id": "$processStatus", "count": {"$sum": 1}}},
                 {"$sort": {"count": -1}}]""";
        return toCountMap(runAggregation(databaseId, collection, excludeInternal));
    }

    /** The non-{@code @cloudfuze.com} owner emails present in a collection, with their row counts. */
    public Map<String, Long> countByOwnerEmail(long databaseId, String collection) {
        String pipeline = """
                [{"$group": {"_id": "$ownerEmailId", "count": {"$sum": 1}}},
                 {"$sort": {"count": -1}}]""";
        return toCountMap(runAggregation(databaseId, collection, pipeline));
    }

    private Map<String, Long> toCountMap(JsonNode rows) {
        // Insertion-ordered so the caller keeps Metabase's own ordering unless it re-sorts.
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            // /api/dataset returns rows as positional arrays matching the "cols" order, which for this
            // pipeline is [_id, count].
            if (!row.isArray() || row.size() < 2) {
                continue;
            }
            JsonNode key = row.get(0);
            String status = key.isNull() || key.isMissingNode() ? null : key.asText();
            counts.merge(status, row.get(1).asLong(), Long::sum);
        }
        return counts;
    }

    /**
     * Posts a MongoDB aggregation pipeline to {@code /api/dataset} and returns the {@code data.rows}
     * array. Mongo, not SQL: Metabase's native query for a Mongo database IS the pipeline JSON, and the
     * collection is named separately from it.
     */
    private JsonNode runAggregation(long databaseId, String collection, String pipelineJson) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Metabase isn't configured -- ask an admin to set METABASE_BASE_URL and either "
                            + "METABASE_API_KEY or METABASE_USERNAME/METABASE_PASSWORD.");
        }
        ObjectNode native_ = objectMapper.createObjectNode();
        native_.put("collection", collection);
        native_.put("query", pipelineJson);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "native");
        body.put("database", databaseId);
        body.set("native", native_);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the Metabase query.");
        }

        HttpResponse<String> response = postAuthenticated(base() + "/api/dataset", payload);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, StringUtils.hasText(apiKey)
                    ? "Metabase rejected our API key -- check METABASE_API_KEY (it may have been revoked)."
                    : "Metabase rejected our credentials -- check METABASE_USERNAME and METABASE_PASSWORD.");
        }
        if (response.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Metabase responded with HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Metabase returned a response we couldn't read as JSON.");
        }
        // /api/dataset answers HTTP 200 even for a failed query -- the failure is in the body. Reading
        // "rows" without checking status would silently return an empty list, i.e. "no work done".
        String status = root.path("status").asText("");
        if (!"completed".equals(status)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Metabase could not run the query: " + shorten(root.path("error").asText("(no detail)")));
        }
        return root.path("data").path("rows");
    }

    /**
     * GET with whichever credential is configured. On the session path a 401 means the cached token
     * has been invalidated server-side, so the token is dropped and the request retried once with a
     * fresh login -- a second 401 is a real credential problem and is returned to the caller.
     */
    private HttpResponse<String> sendAuthenticated(String url) {
        if (StringUtils.hasText(apiKey)) {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build());
        }

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("X-Metabase-Session", currentSessionToken())
                .header("Accept", "application/json")
                .GET()
                .build());
        if (response.statusCode() != 401) {
            return response;
        }
        sessionToken = null;
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("X-Metabase-Session", currentSessionToken())
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private String currentSessionToken() {
        String cached = sessionToken;
        if (cached != null) {
            return cached;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("username", username);
        payload.put("password", password);

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build the Metabase login request.");
        }

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base() + "/api/session"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Metabase rejected our login -- check METABASE_USERNAME and METABASE_PASSWORD.");
        }
        if (response.statusCode() >= 400) {
            // Deliberately does not echo the body: a failed login response is the one place a token
            // could appear in an error message.
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Metabase login failed with HTTP " + response.statusCode() + ".");
        }

        String token;
        try {
            token = objectMapper.readTree(response.body()).path("id").asText(null);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Metabase's login response wasn't readable as JSON.");
        }
        if (!StringUtils.hasText(token)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Metabase's login response carried no session id.");
        }
        sessionToken = token;
        return token;
    }

    /** POST counterpart of {@link #sendAuthenticated}, same retry-once-on-401 session handling. */
    private HttpResponse<String> postAuthenticated(String url, String jsonBody) {
        if (StringUtils.hasText(apiKey)) {
            return send(postRequest(url, jsonBody).header("x-api-key", apiKey).build());
        }
        HttpResponse<String> response =
                send(postRequest(url, jsonBody).header("X-Metabase-Session", currentSessionToken()).build());
        if (response.statusCode() != 401) {
            return response;
        }
        sessionToken = null;
        return send(postRequest(url, jsonBody).header("X-Metabase-Session", currentSessionToken()).build());
    }

    private HttpRequest.Builder postRequest(String url, String jsonBody) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // The cause has to reach both the log AND the message. Every one of these is an
            // IOException, and they need completely different fixes:
            //   UnknownHostException   -- the hostname does not resolve from THIS machine (no DNS
            //                             record, no hosts entry). metabase.cloudfuze.com is not in
            //                             public DNS, so a server without a hosts entry lands here.
            //   ConnectException       -- resolved, but nothing accepted the connection (wrong port,
            //                             firewall, service down).
            //   HttpConnectTimeout...  -- no answer at all, usually a firewall dropping packets.
            //   SSLHandshakeException  -- reachable, but the certificate does not match (typical when
            //                             METABASE_BASE_URL is an IP rather than the hostname).
            // Collapsing all four into one sentence made this undiagnosable from the screen and from
            // the log, which cost real time: it had to be worked out from outside the server.
            log.warn("Could not reach Metabase at {}: {}", request.uri(), e.toString(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Metabase at " + request.uri().getHost() + " ("
                            + e.getClass().getSimpleName() + "). Check METABASE_BASE_URL.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The Metabase request was interrupted -- try again.");
        }
    }

    private String base() {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
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
