package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

// Result of a server-side reachability probe of a ticket URL (see UrlValidationService). Kept small
// and stable so the frontend can just render ok -> green check / not-ok -> red X plus the message.
@Getter
@Setter
public class UrlValidationResult {

    // True when the URL is well-formed, passed SSRF checks, and the server got an HTTP response that
    // indicates the link exists (any response < 400, plus 401/403 which mean "exists but sign-in
    // required").
    private boolean ok;

    // The HTTP status code the target returned, or null if we never got that far (bad URL / blocked
    // host / connection failure).
    private Integer status;

    // Human-readable explanation shown next to the field.
    private String message;

    public UrlValidationResult() {
    }

    public UrlValidationResult(boolean ok, Integer status, String message) {
        this.ok = ok;
        this.status = status;
        this.message = message;
    }
}
