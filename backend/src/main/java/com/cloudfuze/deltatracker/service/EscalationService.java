package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.EscalationCreateRequest;
import com.cloudfuze.deltatracker.dto.EscalationDto;
import com.cloudfuze.deltatracker.dto.EscalationResolveRequest;
import com.cloudfuze.deltatracker.entity.Escalation;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.EscalationRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class EscalationService {

    private final EscalationRepository escalationRepository;
    private final ServerRepository serverRepository;

    public EscalationService(EscalationRepository escalationRepository, ServerRepository serverRepository) {
        this.escalationRepository = escalationRepository;
        this.serverRepository = serverRepository;
    }

    public List<EscalationDto> listAll() {
        return escalationRepository.findAll().stream()
                .sorted(Comparator.comparing(Escalation::getCreatedAt).reversed())
                .map(EscalationDto::fromEntity)
                .toList();
    }

    public EscalationDto create(EscalationCreateRequest request) {
        Server server = serverRepository.findById(request.getServerId())
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + request.getServerId()));

        if (request.getStatus() == EscalationStatus.RESOLVED && !StringUtils.hasText(request.getResolutionNotes())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Resolution notes are required when logging a ticket as Resolved.");
        }

        String ticketNumber = request.getTicketNumber().trim();
        if (escalationRepository.existsByTicketNumberIgnoreCase(ticketNumber)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ticket number '" + ticketNumber + "' has already been logged.");
        }

        Escalation escalation = new Escalation(server, ticketNumber, request.getDescription(),
                request.getReason(), request.getCreatedBy());
        escalation.setStatus(request.getStatus());
        escalation.setPriority(request.getPriority());
        escalation.setResolutionNotes(request.getResolutionNotes());
        escalation.setEvidenceFilePath(request.getEvidenceFilePath());
        escalation.setEvidenceFileName(request.getEvidenceFileName());

        return EscalationDto.fromEntity(escalationRepository.save(escalation));
    }

    public EscalationDto resolve(Long id, EscalationResolveRequest request) {
        Escalation escalation = escalationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Escalation not found: " + id));

        escalation.setStatus(EscalationStatus.RESOLVED);
        escalation.setResolutionNotes(request.getResolutionNotes());
        return EscalationDto.fromEntity(escalationRepository.save(escalation));
    }

    public long countOpenForServer(Long serverId) {
        return escalationRepository.findByServerId(serverId).stream()
                .filter(e -> e.getStatus() == EscalationStatus.OPEN)
                .count();
    }

    public long countOpen() {
        return escalationRepository.countByStatus(EscalationStatus.OPEN);
    }
}
