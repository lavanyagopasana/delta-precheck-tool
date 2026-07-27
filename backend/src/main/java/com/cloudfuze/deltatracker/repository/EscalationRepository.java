package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Escalation;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscalationRepository extends JpaRepository<Escalation, Long> {

    List<Escalation> findByStatus(EscalationStatus status);

    List<Escalation> findByServerId(Long serverId);

    long countByStatus(EscalationStatus status);

    boolean existsByTicketNumberIgnoreCase(String ticketNumber);
}
