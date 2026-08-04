package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.JiraIssueDto;
import com.cloudfuze.deltatracker.dto.TicketCreateRequest;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.dto.TicketUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class TicketService {

    private final TicketRepository ticketRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final JiraService jiraService;
    // Used only by create() to open one explicit transaction around just the DB work, after the
    // (session-free) Jira fetch -- see the comment on create() for why.
    private final TransactionTemplate transactionTemplate;

    public TicketService(TicketRepository ticketRepository, WorkspaceCombinationRepository workspaceCombinationRepository,
                          JiraService jiraService, PlatformTransactionManager transactionManager) {
        this.ticketRepository = ticketRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.jiraService = jiraService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // Tickets are scoped to the team of the project the server belongs to -- same visibility model as
    // sign-offs/projects: ADMIN + Dev/QA Leads see all; a Migration Manager sees tickets for projects
    // they manage; an engineer sees tickets for projects they created or are assigned to. Other
    // members (e.g. an engineer on a different project) don't see them.
    public List<TicketDto> listAll(String callerEmail, AppUserRole callerRole) {
        return ticketRepository.findAllWithCombinationServerAndProject().stream()
                .filter(t -> isVisible(t, callerEmail, callerRole))
                .sorted(Comparator.comparing(Ticket::getCreatedAt).reversed())
                .map(TicketDto::fromEntity)
                .toList();
    }

    // Deliberately NOT transactional at the method level (overrides the class-level default):
    // jiraService.fetchIssue() does blocking network I/O against an external system, and this
    // class's own Hikari config comment already calls out holding a DB connection open across a
    // slow external call as the exact footgun to avoid. Everything that actually touches the
    // database -- including TicketDto.fromEntity's lazy combination.getServer().getProject() lookup
    // -- has to happen inside ONE transaction/session together, though, or it throws
    // LazyInitializationException once that session closes; TransactionTemplate opens that single
    // explicit transaction just for the DB portion, after the Jira call has already finished.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketDto create(TicketCreateRequest request) {
        JiraIssueDto issue = jiraService.fetchIssue(request.getTicketNumber());

        return transactionTemplate.execute(status -> {
            WorkspaceCombination combination = workspaceCombinationRepository.findById(request.getCombinationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Combination not found: " + request.getCombinationId()));

            if (ticketRepository.existsByTicketUrlIgnoreCase(issue.getUrl())) {
                throw new ApiException(HttpStatus.CONFLICT, "This ticket has already been logged.");
            }

            Ticket ticket = new Ticket(combination, issue.getUrl(), request.getCreatedBy());
            ticket.setStatus(issue.isResolved() ? TicketStatus.RESOLVED : TicketStatus.OPEN);
            ticket.setJiraKey(issue.getKey());
            ticket.setJiraSummary(issue.getSummary());
            ticket.setJiraReporter(issue.getReporterDisplayName());
            ticket.setJiraCreatedAt(issue.getCreatedAt());
            return TicketDto.fromEntity(ticketRepository.save(ticket));
        });
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
        WorkspaceCombination combination = ticket.getCombination();
        Project project = combination != null && combination.getServer() != null
                ? combination.getServer().getProject()
                : null;
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

    // Aggregate across every combination on this server -- used by ServerReadinessDto's server-wide
    // ticket count. For a single combination's own count, see countOpenForCombination below.
    public long countOpenForServer(Long serverId) {
        return ticketRepository.countByCombination_Server_IdAndStatus(serverId, TicketStatus.OPEN);
    }

    public long countOpenForCombination(Long combinationId) {
        return ticketRepository.countByCombinationIdAndStatus(combinationId, TicketStatus.OPEN);
    }

    public long countOpen() {
        return ticketRepository.countByStatus(TicketStatus.OPEN);
    }
}
