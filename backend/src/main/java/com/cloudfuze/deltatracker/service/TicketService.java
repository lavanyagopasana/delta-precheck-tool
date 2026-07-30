package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.TicketCreateRequest;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.dto.TicketUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ServerRepository serverRepository;

    public TicketService(TicketRepository ticketRepository, ServerRepository serverRepository) {
        this.ticketRepository = ticketRepository;
        this.serverRepository = serverRepository;
    }

    // Tickets are scoped to the team of the project the server belongs to -- same visibility model as
    // sign-offs/projects: ADMIN + Dev/QA Leads see all; a Migration Manager sees tickets for projects
    // they manage; an engineer sees tickets for projects they created or are assigned to. Other
    // members (e.g. an engineer on a different project) don't see them.
    public List<TicketDto> listAll(String callerEmail, AppUserRole callerRole) {
        return ticketRepository.findAllWithServerAndProject().stream()
                .filter(t -> isVisible(t, callerEmail, callerRole))
                .sorted(Comparator.comparing(Ticket::getCreatedAt).reversed())
                .map(TicketDto::fromEntity)
                .toList();
    }

    public TicketDto create(TicketCreateRequest request) {
        Server server = serverRepository.findById(request.getServerId())
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + request.getServerId()));

        String ticketUrl = request.getTicketUrl().trim();
        if (ticketRepository.existsByTicketUrlIgnoreCase(ticketUrl)) {
            throw new ApiException(HttpStatus.CONFLICT, "This ticket link has already been logged.");
        }

        Ticket ticket = new Ticket(server, ticketUrl, request.getCreatedBy());
        ticket.setStatus(request.getStatus());
        return TicketDto.fromEntity(ticketRepository.save(ticket));
    }

    public TicketDto resolve(Long id, String callerEmail, AppUserRole callerRole) {
        Ticket ticket = requireManageable(id, callerEmail, callerRole);
        ticket.setStatus(TicketStatus.RESOLVED);
        return TicketDto.fromEntity(ticketRepository.save(ticket));
    }

    // Edit an existing ticket. Same team-scoped access as viewing it -- if you can see it, you can
    // edit it (managers, engineers, QA, dev on that project; admins anywhere).
    public TicketDto update(Long id, TicketUpdateRequest request, String callerEmail, AppUserRole callerRole) {
        Ticket ticket = requireManageable(id, callerEmail, callerRole);

        String ticketUrl = request.getTicketUrl().trim();
        // Ticket links are globally unique -- allow keeping the same one, reject colliding with a
        // different ticket.
        if (!ticketUrl.equalsIgnoreCase(ticket.getTicketUrl())
                && ticketRepository.existsByTicketUrlIgnoreCase(ticketUrl)) {
            throw new ApiException(HttpStatus.CONFLICT, "This ticket link has already been logged.");
        }

        ticket.setTicketUrl(ticketUrl);
        ticket.setStatus(request.getStatus());
        return TicketDto.fromEntity(ticketRepository.save(ticket));
    }

    public void delete(Long id, String callerEmail, AppUserRole callerRole) {
        Ticket ticket = requireManageable(id, callerEmail, callerRole);
        ticketRepository.delete(ticket);
    }

    private Ticket requireManageable(Long id, String callerEmail, AppUserRole callerRole) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        if (!isVisible(ticket, callerEmail, callerRole)) {
            // 404 (not 403) so a non-team member can't even confirm the ticket exists.
            throw new ResourceNotFoundException("Ticket not found: " + id);
        }
        // Editing/resolving/deleting a ticket is limited to the person who logged it (createdBy holds
        // their email) or an admin -- Migration Managers, Dev/QA Leads, and other engineers can view a
        // ticket but not change it. callerEmail == null means auth is off, so everything is permitted.
        if (callerEmail != null
                && callerRole != AppUserRole.ADMIN
                && !callerEmail.equalsIgnoreCase(ticket.getCreatedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only the engineer who logged this ticket (or an admin) can change it.");
        }
        return ticket;
    }

    // Mirrors ProjectService/SignOffService visibility. callerEmail == null means auth isn't
    // configured (everything visible); callerRole == null means an authenticated-but-unrecognized
    // account (sees nothing).
    private boolean isVisible(Ticket ticket, String callerEmail, AppUserRole callerRole) {
        if (callerEmail == null) {
            return true;
        }
        if (callerRole == null) {
            return false;
        }
        if (callerRole == AppUserRole.ADMIN || callerRole == AppUserRole.DEV_LEAD || callerRole == AppUserRole.QA_LEAD) {
            return true;
        }
        Project project = ticket.getServer() != null ? ticket.getServer().getProject() : null;
        if (project == null) {
            return false;
        }
        if (callerRole == AppUserRole.MIGRATION_MANAGER) {
            return callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
        }
        if (callerRole == AppUserRole.MIGRATION_ENGINEER) {
            return callerEmail.equalsIgnoreCase(project.getCreatedBy())
                    || project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
        }
        return false;
    }

    public long countOpenForServer(Long serverId) {
        return ticketRepository.findByServerId(serverId).stream()
                .filter(t -> t.getStatus() == TicketStatus.OPEN)
                .count();
    }

    public long countOpen() {
        return ticketRepository.countByStatus(TicketStatus.OPEN);
    }
}
