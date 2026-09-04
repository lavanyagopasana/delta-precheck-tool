package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.PreCheckItemEvidence;
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
    // The three trails added 2026-09-03. Each holds a FK to a row this service deletes, so the
    // purge has to clear them first or the delete is refused.
    @Mock private com.cloudfuze.deltatracker.repository.PreCheckItemEvidenceRepository preCheckItemEvidenceRepository;
    @Mock private com.cloudfuze.deltatracker.repository.PreCheckItemEditRepository preCheckItemEditRepository;
    @Mock private com.cloudfuze.deltatracker.repository.DeltaCycleItemEvidenceRepository deltaCycleItemEvidenceRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ServerPurgeService service;
    private Server server;
    private WorkspaceCombination combination;

    @BeforeEach
    void setUp() {
        service = new ServerPurgeService(serverRepository, workspaceCombinationRepository, preCheckItemRepository,
                preCheckSubmissionRepository, signOffRepository, ticketRepository, deltaCycleRepository,
                deltaCycleItemRepository, deltaCycleSignOffRepository, preCheckItemEvidenceRepository,
                preCheckItemEditRepository, deltaCycleItemEvidenceRepository, fileStorageService, jdbcTemplate);

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

    /**
     * Regression: per-cycle snapshot evidence was orphaned on disk forever.
     *
     * <p>A decline hands file ownership to the DeltaCycleItem snapshot and CLEARS the live
     * PreCheckItem's path, so for any combination that was ever declined the snapshot row is the only
     * thing that still knows where those files are. purgeDeltaCycles deleted those rows without
     * deleting the files, so purging left the bytes stranded with nothing left to find them by. That
     * broke the assumption that deleting a project after decommission reclaims its storage, and it
     * matters far more now the per-file limit is 1GB rather than 20MB.
     */
    @Test
    void deletesEvidenceFilesBehindPerCycleSnapshotsToo() {
        DeltaCycle cycle = new DeltaCycle();
        cycle.setId(77L);
        when(deltaCycleRepository.findByCombinationIdOrderByCycleNumberAsc(CID)).thenReturn(List.of(cycle));

        DeltaCycleItem snapshot = new DeltaCycleItem();
        snapshot.setEvidenceFilePath("/uploads/declined-cycle-evidence.png");
        DeltaCycleItem noEvidence = new DeltaCycleItem();
        when(deltaCycleItemRepository.findByCycleIdInOrderBySortOrderAsc(List.of(77L)))
                .thenReturn(List.of(snapshot, noEvidence));

        service.purge(server);

        verify(fileStorageService).delete("/uploads/declined-cycle-evidence.png");
        // The null path is handed over as-is; FileStorageService.delete no-ops on blank input rather
        // than making every caller pre-check it.
        verify(fileStorageService).delete(null);
        verify(deltaCycleItemRepository).deleteAll(List.of(snapshot, noEvidence));
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
        //
        // The table-existence probe must be stubbed PRESENT for this case: the cleanup is now guarded,
        // because ddl-auto never CREATES that table either, so on any database newer than the rename
        // the unguarded DELETE failed with `relation "escalations" does not exist`. This test is the
        // older-database half of that pair; ServerPurgeLegacyTableTest covers both halves for real
        // against H2, which is what a mocked JdbcTemplate cannot do.
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        service.purge(server);

        InOrder order = inOrder(jdbcTemplate, serverRepository);
        order.verify(jdbcTemplate).update(anyString(), eq(SID));
        order.verify(serverRepository).delete(server);
    }

    @Test
    void skipsTheLegacyEscalationsCleanupWhenThatTableDoesNotExist() {
        // The regression that made every project/server delete a 500: no entity maps "escalations",
        // so ddl-auto never creates it, and the raw DELETE threw BadSqlGrammarException -- which no
        // GlobalExceptionHandler case matches, surfacing as "Something went wrong. Please try again."
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        service.purge(server);

        verify(jdbcTemplate, never()).update(anyString(), eq(SID));
        verify(serverRepository).delete(server);
    }

    @Test
    void aServerWithNoCombinationsStillGetsDeleted() {
        when(workspaceCombinationRepository.findByServerId(SID)).thenReturn(List.of());

        service.purge(server);

        verify(serverRepository).delete(server);
    }

    /**
     * The regression behind "That conflicts with an existing record" when deleting a server.
     *
     * <p>precheck_item_evidence and precheck_item_edits each hold a FK to precheck_items, so deleting
     * the items first is refused by the database. The purge has to clear the children, and it has to
     * delete the actual FILES behind every evidence row -- not only the item's mirrored first one, or
     * the extras stay on disk with nothing referencing them.
     */
    @Test
    void deletesEvidenceRowsAndTheEditTrailBeforeTheItemsThatOwnThem() {
        PreCheckItem item = new PreCheckItem(combination, "Data Verified");
        item.setId(901L);
        item.setEvidenceFilePath("uploads/first.png");
        when(preCheckItemRepository.findByCombinationId(CID)).thenReturn(List.of(item));

        PreCheckItemEvidence extra = new PreCheckItemEvidence(item, "uploads/second.pdf", "second.pdf", null);
        when(preCheckItemEvidenceRepository.findByItemIdInOrderByUploadedAtAscIdAsc(List.of(901L)))
                .thenReturn(List.of(extra));

        service.purge(server);

        InOrder order = inOrder(preCheckItemEvidenceRepository, preCheckItemEditRepository,
                preCheckItemRepository);
        order.verify(preCheckItemEvidenceRepository).deleteAll(any());
        order.verify(preCheckItemEditRepository).deleteAll(any());
        order.verify(preCheckItemRepository).deleteAll(any());

        // Both files go: the extra evidence row's, and the item's mirrored first one.
        verify(fileStorageService).delete("uploads/second.pdf");
        verify(fileStorageService).delete("uploads/first.png");
    }
}
