package com.cloudfuze.deltatracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Forces a download instead of inline rendering for uploaded evidence whose type a browser would
 * otherwise execute.
 *
 * <p>Why this exists: {@code /uploads/**} is served from the app's own origin and is
 * {@code permitAll()} in {@link SecurityConfig} (the unguessable UUID filename is what stands in
 * for authorization). That combination means any uploaded file a browser renders <em>as markup</em>
 * runs in the application's origin — so an SVG or HTML "evidence" file containing a script tag
 * would be stored XSS against every reviewer who opens the attachment, with access to their
 * session. The same-origin part is what makes it dangerous; a plain download is harmless.
 *
 * <p>The extension allowlist in {@code FileStorageService} already omits these types, but it is
 * only advisory today: {@code app.upload.enforce-validation} defaults to false, so a disallowed
 * extension is logged and <em>still stored</em>. That rollout switch is exactly why this filter is
 * needed as well — it closes the hole for files already on disk and for uploads accepted while
 * enforcement stays off, rather than depending on a flag someone has to remember to flip.
 *
 * <p>Deliberately a denylist of dangerous types rather than an allowlist of safe ones: the safe set
 * is large and grows (every image and media format), while the executable-in-a-browser set is small
 * and well known. A new image format added to {@code ALLOWED_EXTENSIONS} must keep previewing
 * inline without anyone remembering to update this class; a new scriptable format is a deliberate
 * decision that belongs here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UploadDispositionFilter extends OncePerRequestFilter {

    /**
     * Types a browser will parse as markup or script if handed them inline. {@code xml} is included
     * because XHTML and SVG both travel as {@code .xml} often enough to matter, and no evidence
     * workflow needs an XML file rendered rather than downloaded.
     */
    private static final Set<String> FORCE_DOWNLOAD = Set.of(
            "html", "htm", "xhtml", "xht", "shtml", "mhtml", "mht",
            "svg", "svgz", "xml", "xsl", "xslt",
            "js", "mjs", "wasm");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (shouldForceDownload(request.getRequestURI())) {
            // attachment defeats inline rendering; nosniff stops a browser from second-guessing the
            // declared Content-Type and rendering it as markup anyway. Both are needed -- neither is
            // sufficient alone.
            response.setHeader("Content-Disposition", "attachment");
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        chain.doFilter(request, response);
    }

    private static boolean shouldForceDownload(String uri) {
        if (uri == null || !uri.startsWith("/uploads/")) {
            return false;
        }
        int dot = uri.lastIndexOf('.');
        int slash = uri.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == uri.length() - 1) {
            // No extension at all: nothing tells the browser to render it as markup, and stored names
            // are UUID-based, so this is the "unknown blob" case rather than a bypass.
            return false;
        }
        return FORCE_DOWNLOAD.contains(uri.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
