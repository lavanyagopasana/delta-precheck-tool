package com.cloudfuze.deltatracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Permanent visibility into slow requests: logs any request that takes longer than 1s with its
// method, path, status, and duration. Purely observational -- never alters the request or response.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestLoggingFilter.class);
    private static final long SLOW_THRESHOLD_MS = 1000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (elapsedMs > SLOW_THRESHOLD_MS) {
                String query = request.getQueryString();
                log.warn("Slow request: {} {}{} -> {} in {}ms",
                        request.getMethod(),
                        request.getRequestURI(),
                        query != null ? "?" + query : "",
                        response.getStatus(),
                        elapsedMs);
            }
        }
    }
}
