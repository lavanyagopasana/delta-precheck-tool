package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.JiraIssueDto;
import com.cloudfuze.deltatracker.dto.TicketCreateRequest;
import com.cloudfuze.deltatracker.dto.TicketDto;
import com.cloudfuze.deltatracker.dto.TicketUpdateRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketService}: lifecycle, duplicate-URL guard, visibility masking, and the
 * creator/admin-only mutation rule.
 *
 * <p>Tickets are scoped to a WorkspaceCombination now, not a Server directly -- a server can have
 * several combinations, each migrated independently (see the per-combination migration in
 * decisions.md). Creating a ticket also fetches its status/summary/reporter from Jira
 * (JiraService) instead of taking a raw URL/status -- see JiraServiceTest for the fetch itself;
 * here JiraService is mocked to isolate TicketService's own orchestration.
 *
 * <p>{@code transactionManager} is a plain unstubbed mock: {@code create()}'s TransactionTemplate
 * calls {@code getTransaction()}/{@code commit()} on it, which no-op on a mock, so the callback
 * still runs and its result is still returned -- exactly what a unit test needs, with no real
 * transaction semantics involved.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private WorkspaceCombinationRepository workspaceCombinationRepository;

    @Mock
    private JiraService jiraService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TicketService service;

    private WorkspaceCombination combination;

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketRepository, workspaceCombinationRepository, jiraService, transactionManager);
        Project project = new Project("Alpha", "eng@cloudfuze.com", "mgr@cloudfuze.com", null);
        Server server = new Server("SRV-1");
        server.setId(1L);
        server.setProject(project);
        combination = new WorkspaceCombination(server, "Box to OneDrive");
        combination.setId(1L);
    }

    private Ticket ticket(String createdBy, TicketStatus status) {
        Ticket t = new Ticket(combination, "https://jira.example.com/T-1", createdBy);
        t.setId(10L);
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        return t;
    }

    private JiraIssueDto issue(String url, boolean resolved) {
        JiraIssueDto issue = new JiraIssueDto();
        issue.setKey("PROJ-1");
        issue.setUrl(url);
        issue.setSummary("Something broke");
        issue.setResolved(resolved);
        issue.setReporterDisplayName("Jane Reporter");
        issue.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        return issue;
    }

    @Test
    void createFetchesFromJiraAndSaves() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setCombinationId(1L);
        req.setTicketNumber("PROJ-1");
        req.setCreatedBy("eng@cloudfuze.com");
        when(jiraService.fetchIssue("PROJ-1")).thenReturn(issue("https://jira.example.com/T-9", false));
        when(workspaceCombinationRepository.findById(1L)).thenReturn(Optional.of(combination));
        when(ticketRepository.existsByTicketUrlIgnoreCase("https://jira.example.com/T-9")).thenReturn(false);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketDto dto = service.create(req);

        assertThat(dto.getTicketUrl()).isEqualTo("https://jira.example.com/T-9");
        assertThat(dto.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(dto.getCombinationId()).isEqualTo(1L);
        assertThat(dto.getJiraKey()).isEqualTo("PROJ-1");
        assertThat(dto.getJiraReporter()).isEqualTo("Jane Reporter");
    }

    @Test
    void createMapsResolvedJiraStatusCategoryToResolved() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setCombinationId(1L);
        req.setTicketNumber("PROJ-2");
        req.setCreatedBy("eng@cloudfuze.com");
        when(jiraService.fetchIssue("PROJ-2")).thenReturn(issue("https://jira.example.com/T-2", true));
        when(workspaceCombinationRepository.findById(1L)).thenReturn(Optional.of(combination));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketDto dto = service.create(req);

        assertThat(dto.getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void createRejectsUnknownCombination() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setCombinationId(99L);
        req.setTicketNumber("PROJ-3");
        req.setCreatedBy("eng@cloudfuze.com");
        when(jiraService.fetchIssue("PROJ-3")).thenReturn(issue("https://jira.example.com/T-3", false));
        when(workspaceCombinationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(ResourceNotFoundException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateUrl() {
        TicketCreateRequest req = new TicketCreateRequest();
        req.setCombinationId(1L);
        req.setTicketNumber("PROJ-4");
        req.setCreatedBy("eng@cloudfuze.com");
        when(jiraService.fetchIssue("PROJ-4")).thenReturn(issue("https://jira.example.com/T-dup", false));
        when(workspaceCombinationRepository.findById(1L)).thenReturn(Optional.of(combination));
        when(ticketRepository.existsByTicketUrlIgnoreCase("https://jira.example.com/T-dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mutationForbiddenForVisibleNonAdmin() {
        // A Dev Lead can SEE every ticket, but editing/deleting is admin-only -> 403, not applied.
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));
        TicketUpdateRequest req = new TicketUpdateRequest();
        req.setTicketUrl("https://jira.example.com/T-1");
        req.setStatus(TicketStatus.RESOLVED);

        assertThatThrownBy(() -> service.update(10L, req, "dev@cloudfuze.com", AppUserRole.DEV_LEAD))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mutationForbiddenEvenForTheTicketsOwnCreator() {
        // Editing/deleting used to also be allowed for whoever logged the ticket -- that's gone now,
        // it's admin-only. The creator themselves must be rejected just like anyone else non-admin.
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));
        TicketUpdateRequest req = new TicketUpdateRequest();
        req.setTicketUrl("https://jira.example.com/T-1");
        req.setStatus(TicketStatus.RESOLVED);

        assertThatThrownBy(() -> service.update(10L, req, "eng@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER))
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
    void updateKeepingSameUrlIsAllowed() {
        Ticket existing = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        when(ticketRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        TicketUpdateRequest req = new TicketUpdateRequest();
        req.setTicketUrl("https://jira.example.com/T-1"); // same URL, different case handled by service
        req.setStatus(TicketStatus.RESOLVED);

        TicketDto dto = service.update(10L, req, "admin@cloudfuze.com", AppUserRole.ADMIN);

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

        assertThatThrownBy(() -> service.update(10L, req, "admin@cloudfuze.com", AppUserRole.ADMIN))
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
    void deleteUnknownTicketThrowsNotFound() {
        when(ticketRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L, "admin@cloudfuze.com", AppUserRole.ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listAllFiltersByVisibilityAndSortsNewestFirst() {
        Ticket older = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        older.setId(1L);
        older.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        Ticket newer = ticket("eng@cloudfuze.com", TicketStatus.RESOLVED);
        newer.setId(2L);
        newer.setCreatedAt(LocalDateTime.of(2026, 2, 1, 9, 0));
        when(ticketRepository.findAllWithCombinationServerAndProject()).thenReturn(List.of(older, newer));

        List<TicketDto> all = service.listAll("admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getId()).isEqualTo(2L); // newest first
        assertThat(all.get(1).getId()).isEqualTo(1L);
    }

    @Test
    void listAllHidesTicketsFromUnrecognizedAccount() {
        when(ticketRepository.findAllWithCombinationServerAndProject())
                .thenReturn(List.of(ticket("eng@cloudfuze.com", TicketStatus.OPEN)));

        // callerRole == null means an authenticated but unrecognized account -> sees nothing.
        List<TicketDto> all = service.listAll("stranger@external.com", null);

        assertThat(all).isEmpty();
    }

    @Test
    void countOpenForServerAggregatesAcrossCombinations() {
        when(ticketRepository.countByCombination_Server_IdAndStatus(1L, TicketStatus.OPEN)).thenReturn(2L);

        assertThat(service.countOpenForServer(1L)).isEqualTo(2L);
    }

    @Test
    void countOpenForCombinationCountsOnlyThatCombination() {
        when(ticketRepository.countByCombinationIdAndStatus(1L, TicketStatus.OPEN)).thenReturn(1L);

        assertThat(service.countOpenForCombination(1L)).isEqualTo(1L);
    }

    // ---- syncOpenTicketsFromJira: the scheduled poll that closes the loop when a ticket is
    // resolved in Jira after we logged it, without anyone here having to notice first. ----

    private Ticket openJiraTicket(String jiraKey) {
        Ticket t = ticket("eng@cloudfuze.com", TicketStatus.OPEN);
        t.setJiraKey(jiraKey);
        return t;
    }

    @Test
    void syncMarksTicketResolvedWhenJiraSaysDone() {
        Ticket t = openJiraTicket("PROJ-1");
        when(ticketRepository.findByStatusAndJiraKeyIsNotNull(TicketStatus.OPEN)).thenReturn(List.of(t));
        when(jiraService.fetchIssue("PROJ-1")).thenReturn(issue(t.getTicketUrl(), true));

        service.syncOpenTicketsFromJira();

        assertThat(t.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        verify(ticketRepository).save(t);
    }

    @Test
    void syncLeavesTicketOpenWhenJiraStillInProgress() {
        Ticket t = openJiraTicket("PROJ-2");
        when(ticketRepository.findByStatusAndJiraKeyIsNotNull(TicketStatus.OPEN)).thenReturn(List.of(t));
        when(jiraService.fetchIssue("PROJ-2")).thenReturn(issue(t.getTicketUrl(), false));

        service.syncOpenTicketsFromJira();

        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPEN);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void syncSkipsTicketOnJiraErrorWithoutAbortingTheBatch() {
        Ticket broken = openJiraTicket("PROJ-3");
        Ticket healthy = openJiraTicket("PROJ-4");
        when(ticketRepository.findByStatusAndJiraKeyIsNotNull(TicketStatus.OPEN))
                .thenReturn(List.of(broken, healthy));
        when(jiraService.fetchIssue("PROJ-3")).thenThrow(new RuntimeException("Jira unreachable"));
        when(jiraService.fetchIssue("PROJ-4")).thenReturn(issue(healthy.getTicketUrl(), true));

        service.syncOpenTicketsFromJira();

        assertThat(broken.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(healthy.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        verify(ticketRepository).save(healthy);
        verify(ticketRepository, never()).save(broken);
    }

    @Test
    void syncDoesNothingWhenNoOpenJiraTicketsExist() {
        when(ticketRepository.findByStatusAndJiraKeyIsNotNull(TicketStatus.OPEN)).thenReturn(List.of());

        service.syncOpenTicketsFromJira();

        verify(jiraService, never()).fetchIssue(any());
        verify(ticketRepository, never()).save(any());
    }
}
