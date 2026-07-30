package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TicketCreateRequest {

    @NotNull
    private Long serverId;

    // Max matches the ticket_url column length (VARCHAR(512)) so an oversize URL is rejected at bind
    // with a clear message instead of failing at insert.
    @NotBlank
    @Size(max = 512, message = "Ticket URL must be 512 characters or fewer")
    private String ticketUrl;

    @NotBlank
    private String createdBy;

    @NotNull
    private TicketStatus status;
}
