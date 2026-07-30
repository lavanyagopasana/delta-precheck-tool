package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByServerId(Long serverId);

    // Batch variant for building project/list summaries without a query per server.
    List<Ticket> findByServerIdIn(Collection<Long> serverIds);

    // Eager-load server (+ its project) so listAll's visibility check doesn't lazy-load per row.
    @Query("select t from Ticket t join fetch t.server s left join fetch s.project")
    List<Ticket> findAllWithServerAndProject();

    long countByStatus(TicketStatus status);

    boolean existsByTicketUrlIgnoreCase(String ticketUrl);
}
