package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.DeltaCycleItemRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the destructive cascade behind decommissioning a server and deleting a project.
 *
 * <p>What these actually protect: every child FK in this graph is {@code nullable = false}, so the
 * database rejects any deletion order other than children-before-parents. A missing or mis-ordered
 * delete doesn't degrade gracefully -- it throws a raw FK-constraint error mid-transaction. That has
 * already happened once: Delta cycles were added after ProjectService.delete was written and never
 * added to its cascade, so deleting a project with any Delta cycle failed outright.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServerPurgeServiceTest {

    private static final Long SID = 7L;
    private static final Long CID = 70L;
    private static final Long CYCLE_ID = 700L;

    @Mock private ServerRepository serverRepository;
    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private PreCheckItemRepository preCheckItemRepository;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private SignOffRepository signOffRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private DeltaCycleRepository deltaCycleRepository;
    @Mock private DeltaCycleItemRepository deltaCycleItemRepository;
    @Mock private DeltaCycleSignOffRepository deltaCycleSignOffRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ServerPurgeService service;
    private Server server;
    private WorkspaceCombination combination;

    @BeforeEach
    void setUp() {
        service = new ServerPurgeService(serverRepository, workspaceCombinationRepository, preCheckItemRepository,
                preCheckSubmissionRepository, signOffRepository, ticketRepository, deltaCycleRepository,
                deltaCycleItemRepository, deltaCycleSignOffRepository, fileStorageService, jdbcTemplate);

        server = new Server("SRV-1");
        server.setId(SID);
        combination = new WorkspaceCombination(server, "Google to OneDrive");
        combination.setId(CID);

        when(workspaceCombinationRepository.findByServerId(SID)).thenReturn(List.of(combination));
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(List.of());
        when(preCheckSubmissionRepository.findByCombinationId(CID)).thenReturn(Optional.empty());
        when(signOffRepository.findByCombinationId(CID)).thenReturn(List.of());
        when(ticketRepository.findByCombinationId(CID)).thenReturn(List.of());
        when(deltaCycleRepository.findByCombinationIdOrderByCycleNumberAsc(CID)).thenReturn(List.of());
    }

    private DeltaCycle givenOneDeltaCycle() {
        DeltaCycle cycle = new DeltaCycle();
        cycle.setId(CYCLE_ID);
        cycle.setCombination(combination);
        when(deltaCycleRepository.findByCombinationIdOrderByCycleNumberAsc(CID)).thenReturn(List.of(cycle));
        when(deltaCycleItemRepository.findByCycleIdInOrderBySortOrderAsc(List.of(CYCLE_ID)))
                .thenReturn(List.of(new DeltaCycleItem()));
        when(deltaCycleSignOffRepository.findByCycleIdIn(List.of(CYCLE_ID)))
                .thenReturn(List.of(new DeltaCycleSignOff()));
        return cycle;
    }

    @Test
    void deletesTheServerRowItself() {
        service.purge(server);

        verify(serverRepository).delete(server);
    }

    @Test
    void deletesEveryCombinationUnderTheServer() {
        service.purge(server);

        verify(workspaceCombinationRepository).deleteAll(List.of(combination));
    }

    // The regression that motivated extracting this class: these three tables did not exist when the
    // original inline cascade was written, so a project with any Delta cycle could not be deleted.
    @Test
    void deletesDeltaCycleRowsAndTheirChildren() {
        DeltaCycle cycle = givenOneDeltaCycle();

        service.purge(server);

        verify(deltaCycleItemRepository).deleteAll(any());
        verify(deltaCycleSignOffRepository).deleteAll(any());
        verify(deltaCycleRepository).deleteAll(List.of(cycle));
    }

    @Test
    void deletesDeltaCycleChildrenBeforeTheCycleAndTheCycleBeforeTheCombination() {
        DeltaCycle cycle = givenOneDeltaCycle();

        service.purge(server);

        // Both FKs are non-nullable, so this order is a database requirement, not a preference.
        InOrder order = inOrder(deltaCycleItemRepository, deltaCycleSignOffRepository, deltaCycleRepository,
                workspaceCombinationRepository, serverRepository);
        order.verify(deltaCycleItemRepository).deleteAll(any());
        order.verify(deltaCycleSignOffRepository).deleteAll(any());
        order.verify(deltaCycleRepository).deleteAll(List.of(cycle));
        order.verify(workspaceCombinationRepository).deleteAll(List.of(combination));
        order.verify(serverRepository).delete(server);
    }

    @Test
    void skipsDeltaCycleChildLookupsWhenThereAreNoCycles() {
        // Avoids issuing findByCycleIdIn(emptyList()), which some databases reject outright as `IN ()`.
        service.purge(server);

        verify(deltaCycleItemRepository, never()).findByCycleIdInOrderBySortOrderAsc(any());
        verify(deltaCycleSignOffRepository, never()).findByCycleIdIn(any());
    }

    @Test
    void deletesEvidenceFilesFromDiskAlongWithTheirPreCheckItems() {
        PreCheckItem item = new PreCheckItem();
        item.setEvidenceFilePath("/uploads/evidence-1.png");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(List.of(item));

        service.purge(server);

        // Nothing else records where the file lives once the row is gone, so this is the only chance.
        verify(fileStorageService).delete("/uploads/evidence-1.png");
        verify(preCheckItemRepository).deleteAll(List.of(item));
    }

    @Test
    void deletesTheSubmissionSignOffChainAndTickets() {
        PreCheckSubmission submission = new PreCheckSubmission();
        SignOff signOff = new SignOff();
        Ticket ticket = new Ticket();
        when(preCheckSubmissionRepository.findByCombinationId(CID)).thenReturn(Optional.of(submission));
        when(signOffRepository.findByCombinationId(CID)).thenReturn(List.of(signOff));
        when(ticketRepository.findByCombinationId(CID)).thenReturn(List.of(ticket));

        service.purge(server);

        verify(preCheckSubmissionRepository).delete(submission);
        verify(signOffRepository).deleteAll(List.of(signOff));
        verify(ticketRepository).deleteAll(List.of(ticket));
    }

    @Test
    void clearsOrphanedLegacyEscalationRowsBeforeDeletingTheServer() {
        // ddl-auto=update never dropped the pre-rename "escalations" table or its FK, so a leftover row
        // still blocks deleting the server it points at with a raw constraint error.
        service.purge(server);

        InOrder order = inOrder(jdbcTemplate, serverRepository);
        order.verify(jdbcTemplate).update(anyString(), eq(SID));
        order.verify(serverRepository).delete(server);
    }

    @Test
    void aServerWithNoCombinationsStillGetsDeleted() {
        when(workspaceCombinationRepository.findByServerId(SID)).thenReturn(List.of());

        service.purge(server);

        verify(serverRepository).delete(server);
    }
}
