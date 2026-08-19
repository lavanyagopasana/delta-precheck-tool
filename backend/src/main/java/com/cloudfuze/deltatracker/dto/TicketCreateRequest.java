package com.cloudfuze.deltatracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

// Logging a ticket only needs a combination (for internal visibility scoping -- the tracker has no
// concept of our internal Project/Server/Combination, and a server can have several combinations each
// with their own migration) and a ticketNumber; everything else (URL, status, summary, reporter,
// created date) is fetched by TicketLookupService. Despite the name, ticketNumber accepts either a
// bare key ("L1BOAR-15335", or the "CF-29519" alias) or a full ticket URL containing one --
// TicketLookupService.fetchIssue extracts the key either way, so a pasted link works exactly like
// typing the key. See TicketUpdateRequest for editing an already-logged ticket, which still takes a
// raw URL/status.
@Getter
@Setter
public class TicketCreateRequest {

    @NotNull
    private Long combinationId;

    @NotBlank
    private String createdBy;

    @NotBlank
    private String ticketNumber;
}
