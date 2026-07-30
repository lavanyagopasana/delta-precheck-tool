package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.UrlValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Server-side reachability probe for a ticket URL. This has to run on the backend (not the browser)
 * because ticket systems like Jira don't send CORS headers, so a frontend fetch of the link can't
 * read the response. Deliberately NOT {@code @Transactional} -- it does blocking network I/O and must
 * never hold a DB connection while doing so.
 *
 * <p>Because the server fetches a user-supplied URL, this is an SSRF surface: we only allow http/https
 * and refuse to connect to loopback / private / link-local / metadata addresses so it can't be used
 * to probe internal services.
 */
@Service
public class UrlValidationService {

    private static final Logger log = LoggerFactory.getLogger(UrlValidationService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UrlValidationResult validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return new UrlValidationResult(false, null, "Enter a ticket URL to validate.");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            return new UrlValidationResult(false, null, "That doesn't look like a valid URL.");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new UrlValidationResult(false, null, "URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return new UrlValidationResult(false, null, "That doesn't look like a valid URL.");
        }

        try {
            if (isBlockedHost(uri.getHost())) {
                return new UrlValidationResult(false, null, "That host isn't allowed.");
            }
        } catch (UnknownHostException e) {
            return new UrlValidationResult(false, null, "Host not found -- check the URL.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            boolean ok = status < 400 || status == 401 || status == 403;
            String message = ok
                    ? (status == 401 || status == 403
                        ? "Link is reachable (sign-in required to view)."
                        : "Link is reachable.")
                    : "Link responded with HTTP " + status + ".";
            return new UrlValidationResult(ok, status, message);
        } catch (java.io.IOException e) {
            log.debug("Ticket URL validation could not reach {}: {}", uri.getHost(), e.toString());
            return new UrlValidationResult(false, null, "Could not reach that URL.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new UrlValidationResult(false, null, "Validation was interrupted -- try again.");
        }
    }

    // Refuse SSRF-prone targets: resolve the host and reject if any address is loopback, private,
    // link-local (covers the 169.254.169.254 cloud metadata endpoint), wildcard, or multicast.
    private boolean isBlockedHost(String host) throws UnknownHostException {
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                return true;
            }
        }
        return false;
    }
}
