package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// Editing an existing ticket. serverId/createdBy aren't editable -- a ticket stays with the server
// and reporter it was logged against.
@Getter
@Setter
public class TicketUpdateRequest {

    // Max matches the ticket_url column length (VARCHAR(512)) -- see TicketCreateRequest.
    @NotBlank
    @Size(max = 512, message = "Ticket URL must be 512 characters or fewer")
    private String ticketUrl;

    @NotNull
    private TicketStatus status;
}
