package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApproveSignOffRequest {

    // Only used as a fallback when auth isn't configured (no JWT to extract identity from). When
    // auth is on, the caller's JWT is authoritative and this field is ignored.
    private String approverEmail;

    // Only used (and required) when approving as Dev Lead: whether this server also needs QA Lead
    // approval. Ignored for every other role.
    private Boolean qaRequired;
}
