package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.DeltaCycleItem;
import com.cloudfuze.deltatracker.repository.DeltaCycleItemRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Permanently erases one Server and everything hanging off it. Extracted so the two callers that need
 * it -- ServerService.decommission (an admin closing out a finished server), ServerService.deleteServer
 * (an admin removing a server at any time), and ProjectService.delete
 * (deleting a whole project) -- share one definition of "everything hanging off a server" instead of
 * each maintaining its own list. That list has already grown once: DeltaCycle/DeltaCycleItem/
 * DeltaCycleSignOff were added after ProjectService.delete was written and never added to its cascade,
 * so deleting a project with any Delta cycle failed on a foreign-key constraint. Centralising it means
 * the next new child table is fixed for both callers at once.
 *
 * Deliberately contains no authorization and no business-rule guard: callers own those (see
 * ServerService.decommission's admin + all-Final-Deltas-complete checks). This class only knows the
 * deletion order.
 */
@Service
@Transactional
public class ServerPurgeService {

    private final ServerRepository serverRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final PreCheckItemRepository preCheckItemRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final SignOffRepository signOffRepository;
    private final TicketRepository ticketRepository;
    private final DeltaCycleRepository deltaCycleRepository;
    private final DeltaCycleItemRepository deltaCycleItemRepository;
    private final DeltaCycleSignOffRepository deltaCycleSignOffRepository;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;

    public ServerPurgeService(ServerRepository serverRepository,
                              WorkspaceCombinationRepository workspaceCombinationRepository,
                              PreCheckItemRepository preCheckItemRepository,
                              PreCheckSubmissionRepository preCheckSubmissionRepository,
                              SignOffRepository signOffRepository,
                              TicketRepository ticketRepository,
                              DeltaCycleRepository deltaCycleRepository,
                              DeltaCycleItemRepository deltaCycleItemRepository,
                              DeltaCycleSignOffRepository deltaCycleSignOffRepository,
                              FileStorageService fileStorageService,
                              JdbcTemplate jdbcTemplate) {
        this.serverRepository = serverRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.preCheckItemRepository = preCheckItemRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.signOffRepository = signOffRepository;
        this.ticketRepository = ticketRepository;
        this.deltaCycleRepository = deltaCycleRepository;
        this.deltaCycleItemRepository = deltaCycleItemRepository;
        this.deltaCycleSignOffRepository = deltaCycleSignOffRepository;
        this.fileStorageService = fileStorageService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deletes children before parents throughout -- every child FK in this graph is
     * {@code nullable = false}, so the database rejects any other order rather than cascading for us.
     * Evidence files are removed from disk as their PreCheckItem rows go, since nothing else records
     * where they live once the row is gone.
     */
    public void purge(Server server) {
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getId());

        for (WorkspaceCombination combination : combinations) {
            purgeDeltaCycles(combination.getId());

            List<PreCheckItem> items = preCheckItemRepository.findByCombinationId(combination.getId());
            items.forEach(item -> fileStorageService.delete(item.getEvidenceFilePath()));
            preCheckItemRepository.deleteAll(items);

            preCheckSubmissionRepository.findByCombinationId(combination.getId())
                    .ifPresent(preCheckSubmissionRepository::delete);
            signOffRepository.deleteAll(signOffRepository.findByCombinationId(combination.getId()));
            ticketRepository.deleteAll(ticketRepository.findByCombinationId(combination.getId()));
        }

        workspaceCombinationRepository.deleteAll(combinations);
        deleteOrphanedEscalationRows(server.getId());
        // Workspace pairs cascade via Server's @OneToMany(orphanRemoval = true).
        serverRepository.delete(server);
    }

    // Per-cycle snapshot rows (checklist items + sign-offs) point at the cycle, which points at the
    // combination -- so all three go, deepest first.
    //
    // The evidence files these snapshots reference MUST be deleted here too. A rollover deliberately
    // leaves the file on disk and hands ownership to the DeltaCycleItem snapshot (see that entity's
    // own comment) while CLEARING the live PreCheckItem's path -- so for any combination that was
    // ever declined, the snapshot row is the ONLY thing that still knows where those files are. The
    // live-item cleanup in purge() therefore deletes nothing for them, and before this they were
    // orphaned on disk forever: rows gone, bytes stranded, nothing left to find them by.
    //
    // That leak is why "we delete the project after decommission, so storage takes care of itself"
    // did not actually hold. It matters much more now that a single evidence file can be 1GB.
    private void purgeDeltaCycles(Long combinationId) {
        List<DeltaCycle> cycles = deltaCycleRepository.findByCombinationIdOrderByCycleNumberAsc(combinationId);
        if (cycles.isEmpty()) {
            return;
        }
        List<Long> cycleIds = cycles.stream().map(DeltaCycle::getId).toList();
        List<DeltaCycleItem> cycleItems = deltaCycleItemRepository.findByCycleIdInOrderBySortOrderAsc(cycleIds);
        cycleItems.forEach(item -> fileStorageService.delete(item.getEvidenceFilePath()));
        deltaCycleItemRepository.deleteAll(cycleItems);
        deltaCycleSignOffRepository.deleteAll(deltaCycleSignOffRepository.findByCycleIdIn(cycleIds));
        deltaCycleRepository.deleteAll(cycles);
    }

    // "escalations" is a leftover table from before this app's Escalation concept was renamed to
    // Ticket (table "tickets") -- no entity maps to it anymore, but Hibernate's ddl-auto=update never
    // drops old tables or their foreign keys, so a row left over from that era still blocks deleting
    // the server it points at with a raw FK-constraint error. Plain JDBC since there's no JPA
    // repository (and shouldn't be one) for a table nothing in the codebase otherwise touches.
    private void deleteOrphanedEscalationRows(Long serverId) {
        jdbcTemplate.update("DELETE FROM escalations WHERE server_id = ?", serverId);
    }
}
