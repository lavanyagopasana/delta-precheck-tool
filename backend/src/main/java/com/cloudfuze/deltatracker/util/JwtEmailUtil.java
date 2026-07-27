package com.cloudfuze.deltatracker.util;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

public final class JwtEmailUtil {

    private JwtEmailUtil() {
    }

    public static String extractEmail(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String email = jwt.getClaimAsString("preferred_username");
        if (!StringUtils.hasText(email)) {
            email = jwt.getClaimAsString("email");
        }
        return email;
    }
}
