package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.TicketCreateRequest;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.dto.TicketUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link TicketService}: lifecycle, duplicate-URL guard, visibility masking, and the creator/admin-only mutation rule. */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ServerRepository serverRepository;

    private TicketService service;

    private Server server;

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketRepository, serverRepository);
        Project project = new Project("Alpha", ProductType.MESSAGE, "eng@cloudfuze.com", "mgr@cloudfuze.com", null);
        server = new Server("SRV-1");
        server.setId(1L);
        server.setProject(project);
    }

    private Ticket ticket(String createdBy, TicketStatus status) {
        Ticket t = new Ticket(server, "https://jira.example.com/T-1", createdBy);
        t.setId(10L);
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        return t;
    }

    @Test
    void createTrimsUrlAndSaves() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setServerId(1L);
        req.setTicketUrl("  https://jira.example.com/T-9  ");
        req.setCreatedBy("eng@cloudfuze.com");
        req.setStatus(TicketStatus.OPEN);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(ticketRepository.existsByTicketUrlIgnoreCase("https://jira.example.com/T-9")).thenReturn(false);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketDto dto = service.create(req);

        assertThat(dto.getTicketUrl()).isEqualTo("https://jira.example.com/T-9");
        assertThat(dto.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(dto.getServerId()).isEqualTo(1L);
    }

    @Test
    void createRejectsUnknownServer() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setServerId(99L);
        req.setTicketUrl("https://x");
        req.setCreatedBy("eng@cloudfuze.com");
        req.setStatus(TicketStatus.OPEN);
        when(serverRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(ResourceNotFoundException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateUrl() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setServerId(1L);
        req.setTicketUrl("https://dup");
        req.setCreatedBy("eng@cloudfuze.com");
        req.setStatus(TicketStatus.OPEN);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(server));
        when(ticketRepository.existsByTicketUrlIgnoreCase("https://dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void resolveByCreatorSetsResolved() {
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketDto dto = service.resolve(10L, "eng@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER);

        assertThat(dto.getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void resolveByAdminSetsResolved() {
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketDto dto = service.resolve(10L, "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(dto.getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void mutationForbiddenForVisibleNonCreatorNonAdmin() {
        // A Dev Lead can SEE every ticket, but may not change one they didn't log -> 403, not applied.
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));

        assertThatThrownBy(() -> service.resolve(10L, "dev@cloudfuze.com", AppUserRole.DEV_LEAD))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mutationMaskedAsNotFoundForNonVisibleCaller() {
        // A stranger engineer (not the project creator, not a member) shouldn't even learn it exists.
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));

        assertThatThrownBy(() -> service.delete(10L, "stranger@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(ticketRepository, never()).delete(any());
    }

    @Test
    void resolveUnknownTicketThrowsNotFound() {
        when(ticketRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(404L, "admin@cloudfuze.com", AppUserRole.ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateKeepingSameUrlIsAllowed() {
        Ticket existing = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        TicketUpdateRequest req = new TicketUpdateRequest();
        req.setTicketUrl("https://jira.example.com/T-1"); // same URL, different case handled by service
        req.setStatus(TicketStatus.RESOLVED);

        TicketDto dto = service.update(10L, req, "eng@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER);

        assertThat(dto.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        // Same URL -> no duplicate check against the repository.
        verify(ticketRepository, never()).existsByTicketUrlIgnoreCase(any());
    }

    @Test
    void updateRejectsCollidingWithDifferentTicketUrl() {
        Ticket existing = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(ticketRepository.existsByTicketUrlIgnoreCase("https://jira.example.com/T-OTHER")).thenReturn(true);
        TicketUpdateRequest req = new TicketUpdateRequest();
        req.setTicketUrl("https://jira.example.com/T-OTHER");
        req.setStatus(TicketStatus.OPEN);

        assertThatThrownBy(() -> service.update(10L, req, "eng@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void deleteByAdminRemovesTicket() {
        Ticket existing = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(existing));

        service.delete(10L, "admin@cloudfuze.com", AppUserRole.ADMIN);

        verify(ticketRepository).delete(existing);
    }

    @Test
    void listAllFiltersByVisibilityAndSortsNewestFirst() {
        Ticket older = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        older.setId(1L);
        older.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        Ticket newer = ticket("eng@cloudfuze.com", TicketStatus.RESOLVED);
        newer.setId(2L);
        newer.setCreatedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        when(ticketRepository.findAllWithServerAndProject()).thenReturn(List.of(older, newer));

        List<TicketDto> all = service.listAll("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getId()).isEqualTo(2L); // newest first
        assertThat(all.get(1).getId()).isEqualTo(1L);
    }

    @Test
    void listAllHidesTicketsFromUnrecognizedAccount() {
        when(ticketRepository.findAllWithServerAndProject())
                .thenReturn(List.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));

        // callerRole == null means an authenticated but unrecognized account -> sees nothing.
        List<TicketDto> all = service.listAll("stranger@external.com", null);

        assertThat(all).isEmpty();
    }

    @Test
    void countOpenForServerCountsOnlyOpen() {
        when(ticketRepository.findByServerId(1L)).thenReturn(List.of(
                ticket("eng@cloudfuze.com", TicketStatus.OPEN),
                ticket("eng@cloudfuze.com", TicketStatus.RESOLVED),
                ticket("eng@cloudfuze.com", TicketStatus.OPEN)));

        assertThat(service.countOpenForServer(1L)).isEqualTo(2L);
    }
}
