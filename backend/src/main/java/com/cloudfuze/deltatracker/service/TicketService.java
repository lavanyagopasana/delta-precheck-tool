package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ExternalTicketDto;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final TicketLookupService ticketLookupService;
    // Used only by create() to open one explicit transaction around just the DB work, after the
    // (session-free) tracker fetch -- see the comment on create() for why.
    private final TransactionTemplate transactionTemplate;

    public TicketService(TicketRepository ticketRepository, WorkspaceCombinationRepository workspaceCombinationRepository,
                          TicketLookupService ticketLookupService, PlatformTransactionManager transactionManager) {
        this.ticketRepository = ticketRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.ticketLookupService = ticketLookupService;
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
    // ticketLookupService.fetchIssue() does blocking network I/O against an external system, and this
    // class's own Hikari config comment already calls out holding a DB connection open across a
    // slow external call as the exact footgun to avoid. Everything that actually touches the
    // database -- including TicketDto.fromEntity's lazy combination.getServer().getProject() lookup
    // -- has to happen inside ONE transaction/session together, though, or it throws
    // LazyInitializationException once that session closes; TransactionTemplate opens that single
    // explicit transaction just for the DB portion, after the tracker call has already finished.
    //
    // request.getTicketNumber() accepts either a bare key ("L1BOAR-15335") or a full ticket URL
    // containing one -- TicketLookupService.fetchIssue extracts the key either way, so this method
    // doesn't need to know or care which shape was typed in.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketDto create(TicketCreateRequest request) {
        ExternalTicketDto issue = ticketLookupService.fetchIssue(request.getTicketNumber());

        return transactionTemplate.execute(status -> {
            WorkspaceCombination combination = findCombinationOrThrow(request.getCombinationId());
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

    private WorkspaceCombination findCombinationOrThrow(Long combinationId) {
        return workspaceCombinationRepository.findById(combinationId)
                .orElseThrow(() -> new ResourceNotFoundException("Combination not found: " + combinationId));
    }

    // Keeps an OPEN ticket in sync with the tracker without anyone having to notice the change over
    // there first. Runs every 15 minutes; only re-checks tickets that are still OPEN and actually came
    // from the tracker (jiraKey set) -- a manually logged plain URL has no ticket number to poll, and a
    // ticket we already marked RESOLVED doesn't need re-checking. Deliberately NOT transactional at the
    // method level (same reasoning as create()): each fetch is a slow external call, and this
    // runs one per open ticket in a loop -- wrapping the whole loop in one transaction would hold a
    // DB connection open for the entire batch's worth of tracker round-trips. Each ticket's own
    // read-modify-save is small enough that Spring Data's own per-call transactional proxy on
    // findByStatusAndJiraKeyIsNotNull/save is sufficient without an explicit TransactionTemplate.
    // One ticket's error (e.g. it was deleted from the tracker) is logged and skipped rather than
    // aborting the rest of the batch.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.MINUTES)
    public void syncOpenTicketsFromTracker() {
        List<Ticket> openTrackedTickets = ticketRepository.findByStatusAndJiraKeyIsNotNull(TicketStatus.OPEN);
        if (openTrackedTickets.isEmpty()) {
            return;
        }
        int resolvedCount = 0;
        for (Ticket ticket : openTrackedTickets) {
            try {
                ExternalTicketDto issue = ticketLookupService.fetchIssue(ticket.getJiraKey());
                if (issue.isResolved()) {
                    ticket.setStatus(TicketStatus.RESOLVED);
                    ticketRepository.save(ticket);
                    resolvedCount++;
                }
            } catch (Exception e) {
                log.warn("Could not sync ticket {} ({}) from the ticketing system: {}", ticket.getId(), ticket.getJiraKey(), e.toString());
            }
        }
        if (resolvedCount > 0) {
            log.info("Ticket sync: {} of {} open ticket(s) are now resolved.", resolvedCount, openTrackedTickets.size());
        }
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
        // Editing/deleting a ticket is admin-only -- even the engineer who logged it can no longer
        // change it themselves. callerEmail == null means auth is off, so everything is permitted.
        if (callerEmail != null && callerRole != AppUserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only an admin can change this ticket.");
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

    // All tickets logged against this combination, open or resolved -- the combination detail view
    // shows this instead of just the open count so a resolved ticket doesn't just vanish from the
    // number the moment it's closed.
    public long countTotalForCombination(Long combinationId) {
        return ticketRepository.countByCombinationId(combinationId);
    }

    public long countOpen() {
        return ticketRepository.countByStatus(TicketStatus.OPEN);
    }
}
