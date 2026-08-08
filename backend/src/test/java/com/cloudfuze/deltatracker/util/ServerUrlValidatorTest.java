package com.cloudfuze.deltatracker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://server.example.com",
            "http://server.example.com",
            "https://tenant.company.co.uk",
            "https://server.example.com:8443",
            "https://server.example.com/path/to/thing",
            "https://migsrv01",          // single-label internal hostname
            "https://10.20.30.40",       // internal IP
            "HTTPS://Server.Example.COM" // scheme/host case shouldn't matter
    })
    void acceptsValidServerUrls(String url) {
        assertNull(ServerUrlValidator.validationError(url), "should be valid: " + url);
        assertTrue(ServerUrlValidator.isValid(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://",                  // the exact value that used to be accepted
            "http://",
            "server.example.com",        // no scheme
            "asdf",
            "!!!",
            "ftp://server.example.com",  // unsupported scheme
            "https://exa mple.com",      // space
            "https://-bad-.com",         // label starts/ends with hyphen
            "https://user:pw@host.com"   // embedded credentials
    })
    void rejectsInvalidServerUrls(String url) {
        assertNotNull(ServerUrlValidator.validationError(url), "should be rejected: " + url);
        assertFalse(ServerUrlValidator.isValid(url));
    }

    @Test
    void rejectsBlankAndNull() {
        assertNotNull(ServerUrlValidator.validationError(null));
        assertNotNull(ServerUrlValidator.validationError(""));
        assertNotNull(ServerUrlValidator.validationError("   "));
    }
}
