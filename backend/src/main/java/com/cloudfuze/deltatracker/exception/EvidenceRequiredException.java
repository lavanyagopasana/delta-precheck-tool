package com.cloudfuze.deltatracker.exception;

import org.springframework.http.HttpStatus;

public class EvidenceRequiredException extends ApiException {

    public EvidenceRequiredException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
