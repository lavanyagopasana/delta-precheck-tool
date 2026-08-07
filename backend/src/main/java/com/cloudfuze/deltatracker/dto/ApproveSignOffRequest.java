package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.Size;
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

    // Required when declining, ignored when approving. A decline bounces the chain back a step, so
    // without a reason the engineer is told to redo the work with no indication of what was wrong.
    // Capped to match SignOff.declineReason's column so a long paste fails validation with a clear
    // message rather than a database truncation error.
    @Size(max = 500, message = "Reason must be 500 characters or fewer.")
    private String reason;
}
