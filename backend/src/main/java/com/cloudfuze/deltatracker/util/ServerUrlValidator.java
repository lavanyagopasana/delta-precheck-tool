package com.cloudfuze.deltatracker.util;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Format validation for a "Server URL".
 *
 * Deliberately format-only: it never calls the URL. A liveness check would be the wrong test here
 * (a real migration endpoint sits behind auth and answers 401/403, not 200; an internal host may be
 * unreachable from wherever this backend runs; a server that is merely down is still a valid URL)
 * and letting the backend fetch a user-supplied address is a textbook SSRF vector -- it would let
 * anyone use this app to probe the internal network, cloud metadata endpoints included. If a
 * reachability check is ever wanted it belongs behind an explicit, admin-only "test connection"
 * action with private/loopback ranges blocked, not in the save path.
 *
 * Previously the only rules were @NotBlank + @Size(255), so "https://", "asdf" and "!!!" were all
 * accepted and stored as server URLs.
 */
public final class ServerUrlValidator {

    // Hostname labels: letters/digits/hyphen, not starting or ending with a hyphen. Single-label
    // hosts ("migsrv01") are allowed on purpose -- internal servers frequently have no domain
    // suffix, so requiring a dot would reject legitimate values.
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$");

    private ServerUrlValidator() {
    }

    /**
     * @return null when valid, otherwise a user-facing reason it isn't.
     */
    public static String validationError(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "Server URL is required.";
        }
        String url = rawUrl.trim();

        if (url.chars().anyMatch(Character::isWhitespace)) {
            return "Server URL can't contain spaces.";
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return "That isn't a valid URL. Use the form https://server.example.com";
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return "Include the protocol, e.g. https://server.example.com";
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return "Only http:// and https:// URLs are supported.";
        }

        // "https://" parses fine but has no host -- the exact value that used to be accepted.
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // A URI like "https://my server" leaves host null; getAuthority() tells them apart.
            return uri.getAuthority() == null || uri.getAuthority().isBlank()
                    ? "Add the server address after " + scheme + "://"
                    : "That server address isn't valid.";
        }

        // Credentials embedded in a URL are a security smell and are never needed to identify a server.
        if (uri.getUserInfo() != null) {
            return "Don't include a username or password in the URL.";
        }

        // getHost() returns bracketed IPv6 literals -- accept them without the hostname pattern.
        boolean ipv6Literal = host.startsWith("[") && host.endsWith("]");
        if (!ipv6Literal && !IPV4.matcher(host).matches() && !HOSTNAME.matcher(host).matches()) {
            return "That server address isn't valid.";
        }

        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            return "That port number isn't valid.";
        }

        return null;
    }

    public static boolean isValid(String rawUrl) {
        return validationError(rawUrl) == null;
    }
}
