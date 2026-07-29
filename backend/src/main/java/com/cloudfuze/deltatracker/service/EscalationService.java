package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.EscalationCreateRequest;
import com.cloudfuze.deltatracker.dto.EscalationDto;
import com.cloudfuze.deltatracker.dto.EscalationResolveRequest;
import com.cloudfuze.deltatracker.dto.EscalationUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Escalation;
import com.cloudfuze.deltatracker.entity.EscalationStatus;
import com.cloudfuze.deltatracker.entity.Project;
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
    private final FileStorageService fileStorageService;

    public EscalationService(EscalationRepository escalationRepository, ServerRepository serverRepository,
                             FileStorageService fileStorageService) {
        this.escalationRepository = escalationRepository;
        this.serverRepository = serverRepository;
        this.fileStorageService = fileStorageService;
    }

    // Tickets are scoped to the team of the project the server belongs to -- same visibility model as
    // sign-offs/projects: ADMIN + Dev/QA Leads see all; a Migration Manager sees tickets for projects
    // they manage; an engineer sees tickets for projects they created or are assigned to. Other
    // members (e.g. an engineer on a different project) don't see them.
    public List<EscalationDto> listAll(String callerEmail, AppUserRole callerRole) {
        return escalationRepository.findAll().stream()
                .filter(e -> isVisible(e, callerEmail, callerRole))
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

    public EscalationDto resolve(Long id, EscalationResolveRequest request, String callerEmail, AppUserRole callerRole) {
        Escalation escalation = requireManageable(id, callerEmail, callerRole);

        escalation.setStatus(EscalationStatus.RESOLVED);
        escalation.setResolutionNotes(request.getResolutionNotes());
        return EscalationDto.fromEntity(escalationRepository.save(escalation));
    }

    // Edit an existing ticket. Same team-scoped access as viewing it -- if you can see it, you can
    // edit it (managers, engineers, QA, dev on that project; admins anywhere).
    public EscalationDto update(Long id, EscalationUpdateRequest request, String callerEmail, AppUserRole callerRole) {
        Escalation escalation = requireManageable(id, callerEmail, callerRole);

        String ticketNumber = request.getTicketNumber().trim();
        // Ticket numbers are globally unique -- allow keeping the same one, reject colliding with a
        // different ticket.
        if (!ticketNumber.equalsIgnoreCase(escalation.getTicketNumber())
                && escalationRepository.existsByTicketNumberIgnoreCase(ticketNumber)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ticket number '" + ticketNumber + "' has already been logged.");
        }
        if (request.getStatus() == EscalationStatus.RESOLVED && !StringUtils.hasText(request.getResolutionNotes())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Resolution notes are required when a ticket is Resolved.");
        }

        escalation.setTicketNumber(ticketNumber);
        escalation.setDescription(request.getDescription());
        escalation.setReason(request.getReason());
        escalation.setStatus(request.getStatus());
        escalation.setPriority(request.getPriority());
        escalation.setResolutionNotes(request.getResolutionNotes());
        escalation.setEvidenceFilePath(request.getEvidenceFilePath());
        escalation.setEvidenceFileName(request.getEvidenceFileName());
        return EscalationDto.fromEntity(escalationRepository.save(escalation));
    }

    public void delete(Long id, String callerEmail, AppUserRole callerRole) {
        Escalation escalation = requireManageable(id, callerEmail, callerRole);
        fileStorageService.delete(escalation.getEvidenceFilePath());
        escalationRepository.delete(escalation);
    }

    private Escalation requireManageable(Long id, String callerEmail, AppUserRole callerRole) {
        Escalation escalation = escalationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Escalation not found: " + id));
        if (!isVisible(escalation, callerEmail, callerRole)) {
            // 404 (not 403) so a non-team member can't even confirm the ticket exists.
            throw new ResourceNotFoundException("Escalation not found: " + id);
        }
        return escalation;
    }

    // Mirrors ProjectService/SignOffService visibility. callerEmail == null means auth isn't
    // configured (everything visible); callerRole == null means an authenticated-but-unrecognized
    // account (sees nothing).
    private boolean isVisible(Escalation escalation, String callerEmail, AppUserRole callerRole) {
        if (callerEmail == null) {
            return true;
        }
        if (callerRole == null) {
            return false;
        }
        if (callerRole == AppUserRole.ADMIN || callerRole == AppUserRole.DEV_LEAD || callerRole == AppUserRole.QA_LEAD) {
            return true;
        }
        Project project = escalation.getServer() != null ? escalation.getServer().getProject() : null;
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
        return escalationRepository.findByServerId(serverId).stream()
                .filter(e -> e.getStatus() == EscalationStatus.OPEN)
                .count();
    }

    public long countOpen() {
        return escalationRepository.countByStatus(EscalationStatus.OPEN);
    }
}
