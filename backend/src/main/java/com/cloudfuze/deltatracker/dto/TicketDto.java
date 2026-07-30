package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TicketDto {

    private Long id;
    private Long serverId;
    private String serverName;
    private Long projectId;
    private String projectName;
    private String ticketUrl;
    private String createdBy;
    private LocalDateTime createdAt;
    private TicketStatus status;

    public static TicketDto fromEntity(Ticket ticket) {
        Server server = ticket.getServer();
        Project project = server != null ? server.getProject() : null;
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setServerId(server != null ? server.getId() : null);
        dto.setServerName(server != null ? server.getName() : null);
        dto.setProjectId(project != null ? project.getId() : null);
        dto.setProjectName(project != null ? project.getName() : null);
        dto.setTicketUrl(ticket.getTicketUrl());
        dto.setCreatedBy(ticket.getCreatedBy());
        dto.setCreatedAt(ticket.getCreatedAt());
        dto.setStatus(ticket.getStatus());
        return dto;
    }
}
