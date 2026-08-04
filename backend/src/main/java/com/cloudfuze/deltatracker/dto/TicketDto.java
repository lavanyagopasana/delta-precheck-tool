package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TicketDto {

    private Long id;
    private Long combinationId;
    private String combinationName;
    private Long serverId;
    private String serverName;
    private Long projectId;
    private String projectName;
    private String ticketUrl;
    private String createdBy;
    private LocalDateTime createdAt;
    private TicketStatus status;
    // Read-only snapshot fetched from Jira at logging time -- see JiraService/Ticket entity.
    private String jiraKey;
    private String jiraSummary;
    private String jiraReporter;
    private LocalDateTime jiraCreatedAt;

    public static TicketDto fromEntity(Ticket ticket) {
        WorkspaceCombination combination = ticket.getCombination();
        Server server = combination != null ? combination.getServer() : null;
        Project project = server != null ? server.getProject() : null;
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setCombinationId(combination != null ? combination.getId() : null);
        dto.setCombinationName(combination != null ? combination.getName() : null);
        dto.setServerId(server != null ? server.getId() : null);
        dto.setServerName(server != null ? server.getName() : null);
        dto.setProjectId(project != null ? project.getId() : null);
        dto.setProjectName(project != null ? project.getName() : null);
        dto.setTicketUrl(ticket.getTicketUrl());
        dto.setCreatedBy(ticket.getCreatedBy());
        dto.setCreatedAt(ticket.getCreatedAt());
        dto.setStatus(ticket.getStatus());
        dto.setJiraKey(ticket.getJiraKey());
        dto.setJiraSummary(ticket.getJiraSummary());
        dto.setJiraReporter(ticket.getJiraReporter());
        dto.setJiraCreatedAt(ticket.getJiraCreatedAt());
        return dto;
    }
}
