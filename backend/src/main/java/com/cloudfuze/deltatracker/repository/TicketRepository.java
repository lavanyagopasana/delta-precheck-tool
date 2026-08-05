package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByCombinationId(Long combinationId);

    long countByCombinationIdAndStatus(Long combinationId, TicketStatus status);

    // Aggregate across every combination on a server -- ServerReadinessDto's server-wide ticket
    // count still needs this even though a single ticket now belongs to one specific combination.
    long countByCombination_Server_IdAndStatus(Long serverId, TicketStatus status);

    // Batch variant for building project-level summaries without a query per server. Joins through
    // combination since that's the only path from a ticket to its server now -- fetch-joined so
    // callers can read t.getCombination().getServer() without an extra query per row.
    @Query("select t from Ticket t join fetch t.combination c join fetch c.server where c.server.id in :serverIds")
    List<Ticket> findAllByCombinationServerIdIn(@Param("serverIds") Collection<Long> serverIds);

    // Eager-load combination -> server -> project so listAll's visibility check doesn't lazy-load
    // per row.
    @Query("select t from Ticket t join fetch t.combination c join fetch c.server s left join fetch s.project")
    List<Ticket> findAllWithCombinationServerAndProject();

    long countByStatus(TicketStatus status);

    boolean existsByTicketUrlIgnoreCase(String ticketUrl);

    // Backs the scheduled Jira sync (TicketService.syncOpenTicketsFromJira) -- only tickets that are
    // both still OPEN and actually came from Jira (jiraKey set) are worth re-checking; a manually
    // logged plain URL has no ticket number to poll.
    List<Ticket> findByStatusAndJiraKeyIsNotNull(TicketStatus status);
}
