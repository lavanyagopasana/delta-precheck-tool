package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.CombinationSummaryDto;
import com.cloudfuze.deltatracker.dto.ServerReadinessDto;
import com.cloudfuze.deltatracker.dto.WorkspacePairDto;
import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.PreCheckSubmission;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import com.cloudfuze.deltatracker.util.ServerUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class ServerService {

    public static final String DELTA_TYPE_ITEM = "Delta Type";
    // Content only. Its NOT_APPLICABLE status exempts it from the evidence/note requirement other
    // items carry -- see PreCheckSubmissionService.submit.
    public static final String HYPERLINKS_VERIFIED_ITEM = "Hyperlinks Verified";
    // Only required/counted when Delta Type's own status is PRE_DELTA -- see
    // PreCheckSubmissionService.isPreDeltaMigrationRequired.
    public static final String PRE_DELTA_MIGRATION_ITEM = "Previous Delta Migration";

    // Renamed from "Pre Delta Migration" on 2026-08-06. The item NAME is the matching key (there is no
    // stable item type/code column), and it's persisted per row, so every checklist seeded before the
    // rename still carries the old string. Matching must accept both or the conditional-requirement
    // rule silently stops applying to existing combinations -- the item would become permanently
    // mandatory on a Final delta, with nothing in the UI explaining why. New rows get the new name.
    private static final String LEGACY_PRE_DELTA_MIGRATION_ITEM = "Pre Delta Migration";

    /** True for the Previous Delta Migration item under either its current or pre-rename name. */
    public static boolean isPreDeltaMigrationItem(String itemName) {
        return PRE_DELTA_MIGRATION_ITEM.equals(itemName) || LEGACY_PRE_DELTA_MIGRATION_ITEM.equals(itemName);
    }

    // The Content checklist -- also still the default/fallback list (used for a combination whose
    // server has no product type set, e.g. one created before this field existed).
    // Delta Type is deliberately first: it decides whether PRE_DELTA_MIGRATION_ITEM is required at all
    // (see PreCheckSubmissionService.isPreDeltaMigrationRequired), so answering anything else before it
    // means a conditional item appearing partway down a form the engineer has already started filling.
    // This list is also what orders an existing checklist on read (PreCheckSubmissionService sorts by
    // it rather than by a stored per-row column), so reordering here reorders combinations already in
    // flight -- intended, since the order is presentation, not data.
    public static final List<String> PRE_CHECK_ITEMS = List.of(
            DELTA_TYPE_ITEM,
            "OneTime Migration",
            PRE_DELTA_MIGRATION_ITEM,
            "Data Verified",
            "Permissions Verified",
            HYPERLINKS_VERIFIED_ITEM,
            "Workspace Status Updated in DB",
            "Drive changes"
    );

    /**
     * The Email checklist, confirmed with the team on 2026-08-06. Deliberately shorter than Content:
     * an email migration has no folder permissions, no hyperlinks and no local drive to reconcile, so
     * Permissions Verified / Hyperlinks Verified / Drive changes don't apply. There is also no
     * "Previous Delta Migration" item -- that one exists to record the prior delta's data movement for
     * Content, and Email tracks the same thing through One Time Migration.
     *
     * <p>Delta Type stays first for the same reason it leads the Content list: it settles whether this
     * cycle is a pre-delta or the final one. Every item except Delta Type requires a status, an evidence
     * file and a note, Email included.
     */
    public static final List<String> EMAIL_PRE_CHECK_ITEMS = List.of(
            DELTA_TYPE_ITEM,
            "OneTime Migration",
            "Data Verified",
            "Workspace Status Updated in DB"
    );

    // Message-only. A yes/no capability question (ENABLED / NOT_ENABLED), not a progress state -- see
    // ItemStatus. Named as a constant because the frontend has to match it exactly to scope the
    // dropdown, the same contract DELTA_TYPE_ITEM has.
    public static final String DELTA_MESSAGE_SYNC_ITEM = "Delta Message Sync";

    /**
     * The Message checklist, confirmed with the team on 2026-08-07. Shares Data Verified and Workspace
     * Status Updated in DB with Content unchanged, drops the file-oriented items (Permissions,
     * Hyperlinks, Drive changes, Previous Delta Migration), and adds Delta Message Sync.
     *
     * <p>Two of its items carry non-default status options: OneTime Migration can be partially
     * completed (a chat migration can move some history and not the rest), and Delta Message Sync is
     * enabled/not enabled. Those option sets live on the frontend in statusOptionsFor -- this list only
     * decides which items exist and in what order.
     */
    public static final List<String> MESSAGE_PRE_CHECK_ITEMS = List.of(
            DELTA_TYPE_ITEM,
            "OneTime Migration",
            DELTA_MESSAGE_SYNC_ITEM,
            "Data Verified",
            "Workspace Status Updated in DB"
    );

    // Keeping every list non-empty matters: an empty checklist can never be submitted
    // (PreCheckSubmissionService.submit requires at least one item), so a genuinely empty list would
    // silently lock every combination of that product type out of ever completing its pre-check.
    private static final Map<ProductType, List<String>> PRE_CHECK_ITEMS_BY_PRODUCT_TYPE = Map.of(
            ProductType.CONTENT, PRE_CHECK_ITEMS,
            ProductType.EMAIL, EMAIL_PRE_CHECK_ITEMS,
            ProductType.MESSAGE, MESSAGE_PRE_CHECK_ITEMS
    );

    // The checklist to seed/sort for a combination, based on its server's product type. Falls back
    // to the Content list for a null product type -- Map.of()'s getOrDefault throws on a null key
    // rather than treating it as "not found", so null has to be handled before it ever reaches the map.
    public static List<String> preCheckItemsFor(ProductType productType) {
        if (productType == null) {
            return PRE_CHECK_ITEMS;
        }
        return PRE_CHECK_ITEMS_BY_PRODUCT_TYPE.getOrDefault(productType, PRE_CHECK_ITEMS);
    }

    private final ServerRepository serverRepository;
    private final WorkspacePairRepository workspacePairRepository;
    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final PreCheckSubmissionRepository preCheckSubmissionRepository;
    private final TicketService ticketService;
    private final ProjectRepository projectRepository;
    private final DeltaCycleRepository deltaCycleRepository;
    private final AppUserService appUserService;
    private final ServerPurgeService serverPurgeService;

    public ServerService(ServerRepository serverRepository,
                          WorkspacePairRepository workspacePairRepository,
                          WorkspaceCombinationRepository workspaceCombinationRepository,
                          PreCheckSubmissionRepository preCheckSubmissionRepository,
                          TicketService ticketService,
                          ProjectRepository projectRepository,
                          DeltaCycleRepository deltaCycleRepository,
                          AppUserService appUserService,
                          ServerPurgeService serverPurgeService) {
        this.serverRepository = serverRepository;
        this.workspacePairRepository = workspacePairRepository;
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.preCheckSubmissionRepository = preCheckSubmissionRepository;
        this.ticketService = ticketService;
        this.projectRepository = projectRepository;
        this.deltaCycleRepository = deltaCycleRepository;
        this.appUserService = appUserService;
        this.serverPurgeService = serverPurgeService;
    }

    public Server findOrThrow(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + id));
    }

    public Server save(Server server) {
        return serverRepository.save(server);
    }

    // Creates a Server directly under a project (the "Server URL" add flow on the project page),
    // without requiring a CSV import first. Mirrors WorkspacePairService.importCsvGlobal's
    // new-server path: same case-insensitive per-project uniqueness rule, the same per-project
    // permission check (non-admins must be this project's Migration Manager or a team member). No
    // pre-check seeding happens here anymore -- that's per-combination now (see
    // WorkspaceCombinationService), and a freshly created server has no combinations yet.
    public ServerReadinessDto createForProject(Long projectId, String name, ProductType productType,
                                                String callerEmail, boolean isAdmin) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        if (callerEmail != null && !isAdmin) {
            boolean isManager = callerEmail.equalsIgnoreCase(project.getMigrationManagerName());
            boolean isTeamMember = project.getEngineerEmails().stream().anyMatch(callerEmail::equalsIgnoreCase);
            if (!isManager && !isTeamMember) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Only this project's Migration Manager or team members can add a server here.");
            }
        }

        String trimmed = name.trim();
        // Format check only -- see ServerUrlValidator for why this deliberately doesn't call the URL.
        // Without it @NotBlank was the only gate, so "https://" (no host at all) was stored happily.
        String urlError = ServerUrlValidator.validationError(trimmed);
        if (urlError != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, urlError);
        }
        if (serverRepository.findByProjectIdAndNameIgnoreCase(projectId, trimmed).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "A server with this URL already exists in this project.");
        }
        Server server = new Server(trimmed);
        server.setProject(project);
        server.setProductType(productType);
        server = serverRepository.save(server);
        return buildReadiness(server, false);
    }

    public ServerReadinessDto updateProductType(Long serverId, ProductType productType) {
        Server server = findOrThrow(serverId);
        server.setProductType(productType);
        server = serverRepository.save(server);
        return buildReadiness(server, false);
    }

    // Server.status is a rollup of its combinations' own statuses -- DELTA_READY only once the
    // server has at least one combination AND every one of them is DELTA_READY; PENDING if none of
    // them have any progress; IN_PROGRESS otherwise. Called whenever a combination's own status
    // changes (WorkspaceCombinationService.recomputeStatus).
    public void recomputeStatus(Server server) {
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getId());

        PairStatus newStatus;
        if (combinations.isEmpty()) {
            newStatus = PairStatus.PENDING;
        } else if (combinations.stream().allMatch(c -> c.getStatus() == PairStatus.DELTA_READY)) {
            newStatus = PairStatus.DELTA_READY;
        } else if (combinations.stream().anyMatch(c -> c.getStatus() != PairStatus.PENDING)) {
            newStatus = PairStatus.IN_PROGRESS;
        } else {
            newStatus = PairStatus.PENDING;
        }

        server.setStatus(newStatus);
        serverRepository.save(server);
    }

    /**
     * True when every combination under this server has completed its Final Delta -- i.e. there's no
     * migration work left and the source server can be turned off.
     *
     * <p>Keyed off {@code finalDeltaCompletedAt}, deliberately NOT {@code deltaFinishedAt}: the latter
     * is stamped by every intermediate pre-delta too, so using it would call a server ready after its
     * first of several pre-deltas finished.
     *
     * <p>A server with no combinations is never ready -- otherwise a freshly created, empty server
     * would report itself decommissionable.
     */
    public boolean isDecommissionReady(Server server) {
        return allFinalDeltasComplete(workspaceCombinationRepository.findByServerId(server.getId()));
    }

    // The single definition of "no migration work left", shared with buildReadiness (which already has
    // the combinations loaded and shouldn't re-query for them) so the two can't drift apart.
    private static boolean allFinalDeltasComplete(List<WorkspaceCombination> combinations) {
        return !combinations.isEmpty() && combinations.stream().allMatch(WorkspaceCombination::isFinalDeltaComplete);
    }

    /**
     * Decommissioning ERASES the server. Every combination, workspace pair, pre-check item (and its
     * uploaded evidence file on disk), sign-off, Delta cycle and ticket is deleted, then the Server row
     * itself -- the server disappears from the project entirely. This is irreversible by design
     * (product decision, 2026-08-06): a decommissioned server is finished work that shouldn't keep
     * accumulating in the tool. There is deliberately no undo, which is why the previous reinstate()
     * was removed rather than kept alongside -- with the rows gone there would be nothing to restore.
     *
     * Note this destroys the sign-off/evidence audit trail for the server. That was accepted knowingly.
     * If an external record is ever needed, it has to be exported BEFORE this runs.
     *
     * ADMIN-only, enforced both here and in SecurityConfig -- the same defense-in-depth split
     * AppUserService.requireAdmin uses for the allowlist, so the rule survives a routing change. The
     * all-Final-Deltas-complete guard is what stops this being a way to delete in-flight work.
     */
    public void decommission(Long serverId, String callerEmail) {
        requireAdmin(callerEmail);
        Server server = findOrThrow(serverId);

        if (!isDecommissionReady(server)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Every combination on this server must complete its Final Delta before it can be decommissioned.");
        }

        serverPurgeService.purge(server);
    }

    /**
     * Permanently deletes a server and everything under it (combinations, pairs, pre-check, sign-offs,
     * Delta cycles, tickets, evidence files). Same cascade as {@link #decommission}, but available to
     * admins at any time — no Final-Delta readiness guard. Use when removing a server that was added
     * by mistake or clearing in-progress work; use decommission when closing out finished migration work.
     */
    public void deleteServer(Long serverId, String callerEmail) {
        requireAdmin(callerEmail);
        Server server = findOrThrow(serverId);
        serverPurgeService.purge(server);
    }

    // Not AppUserService.requireAdmin: that one's message is specific to managing app access, and this
    // isn't that. A null email means auth isn't configured, which the whole app deliberately treats as
    // open (see SecurityConfig.authConfigured).
    private void requireAdmin(String callerEmail) {
        if (callerEmail != null && !appUserService.isAdmin(callerEmail)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only an admin can delete or decommission a server.");
        }
    }

    public ServerReadinessDto assignProject(Long serverId, Long projectId) {
        Server server = findOrThrow(serverId);
        if (projectId == null) {
            server.setProject(null);
        } else {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
            server.setProject(project);
        }
        Server saved = serverRepository.save(server);
        return buildReadiness(saved, false);
    }

    public List<ServerReadinessDto> listReadiness() {
        return serverRepository.findAll().stream()
                .map(server -> buildReadiness(server, false))
                .toList();
    }

    // includePairs=true here (unlike listReadiness/assignProject) so the project page's "Servers &
    // Migration Pairs" card can derive each server's already-uploaded combinations without a
    // separate round trip per server.
    public List<ServerReadinessDto> listReadinessForProject(Long projectId) {
        return serverRepository.findAll().stream()
                .filter(server -> server.getProject() != null && server.getProject().getId().equals(projectId))
                .map(server -> buildReadiness(server, true))
                .toList();
    }

    public ServerReadinessDto getReadiness(Long serverId) {
        Server server = findOrThrow(serverId);
        return buildReadiness(server, true);
    }

    private ServerReadinessDto buildReadiness(Server server, boolean includePairs) {
        List<WorkspacePair> pairs = workspacePairRepository.findByServerId(server.getId());
        List<WorkspaceCombination> combinations = workspaceCombinationRepository.findByServerId(server.getId());

        int total = pairs.size();
        int ready = server.getStatus() == PairStatus.DELTA_READY ? total : 0;
        int notReady = total - ready;
        long openEscalations = ticketService.countOpenForServer(server.getId());

        ServerReadinessDto dto = new ServerReadinessDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setStatus(server.getStatus());
        dto.setProductType(server.getProductType());
        dto.setTotalPairs(total);
        dto.setReadyCount(ready);
        dto.setNotReadyCount(notReady);
        dto.setOpenEscalationCount(openEscalations);
        dto.setReadinessStatus(ServerReadinessDto.computeReadinessStatus(server.getStatus(), openEscalations));
        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
            dto.setMigrationManagerName(server.getProject().getMigrationManagerName());
        }

        List<Long> combinationIds = combinations.stream().map(WorkspaceCombination::getId).toList();
        Map<Long, PreCheckSubmission> submissionByCombination = preCheckSubmissionRepository
                .findByCombinationIdIn(combinationIds).stream()
                .collect(Collectors.toMap(s -> s.getCombination().getId(), s -> s));
        // One query for every combination's cycle count rather than one per combination -- this method
        // runs once per server in listReadiness/listReadinessForProject, so a per-combination query here
        // would be an N*M round trip on the projects page.
        Map<Long, Long> completedCycleCountByCombination = deltaCycleRepository.findByCombinationIdIn(combinationIds).stream()
                .filter(cycle -> cycle.getStatus() == DeltaCycleStatus.COMPLETED)
                .collect(Collectors.groupingBy(DeltaCycle::getCombinationId, Collectors.counting()));

        dto.setCombinations(combinations.stream()
                .map(c -> {
                    long pairCount = pairs.stream().filter(p -> sameCombination(p.getCombination(), c.getName())).count();
                    CombinationSummaryDto summary = new CombinationSummaryDto();
                    summary.setId(c.getId());
                    summary.setName(c.getName());
                    summary.setPairCount((int) pairCount);
                    summary.setStatus(c.getStatus());
                    summary.setSubmissionStatus(java.util.Optional.ofNullable(submissionByCombination.get(c.getId()))
                            .map(PreCheckSubmission::getStatus)
                            .orElse(SubmissionStatus.NOT_STARTED));
                    summary.setPreCheckStartedByEmail(
                            java.util.Optional.ofNullable(submissionByCombination.get(c.getId()))
                                    .map(PreCheckSubmission::getStartedByEmail)
                                    .orElse(null));
                    summary.setCurrentCycleNumber(c.getCurrentCycleNumber());
                    summary.setCurrentDeltaType(c.getCurrentDeltaType());
                    // Phase-aware label: "Pre-Delta 1 started" rather than a bare "Pre-Delta 1", which
                    // read identically whether the cycle was awaiting approval, running, or done.
                    com.cloudfuze.deltatracker.entity.DeltaPhase phase =
                            com.cloudfuze.deltatracker.entity.DeltaPhase.of(c);
                    summary.setDeltaPhase(phase);
                    summary.setCurrentDeltaLabel(c.getCurrentDeltaType() == null
                            ? null
                            : c.getCurrentDeltaType().labelWithPhase(c.getCurrentCycleNumber(), phase));
                    // Prior fully finished cycles only — Pre-Delta 1 after approval is never "1 done".
                    long priorCompleted = 0L;
                    if (c.getCurrentCycleNumber() > 1) {
                        priorCompleted = deltaCycleRepository.findByCombinationIdOrderByCycleNumberAsc(c.getId()).stream()
                                .filter(cycle -> cycle.getStatus() == DeltaCycleStatus.COMPLETED)
                                .filter(cycle -> cycle.getCycleNumber() < c.getCurrentCycleNumber())
                                .count();
                    }
                    summary.setCompletedCycleCount(priorCompleted);
                    summary.setFinalDeltaComplete(c.isFinalDeltaComplete());
                    return summary;
                })
                .toList());

        int finalDeltaComplete = (int) combinations.stream().filter(WorkspaceCombination::isFinalDeltaComplete).count();
        dto.setFinalDeltaCompleteCount(finalDeltaComplete);
        dto.setTotalDeltaCycleCount(completedCycleCountByCombination.values().stream().mapToLong(Long::longValue).sum());
        dto.setDecommissionedAt(server.getDecommissionedAt());
        dto.setDecommissionedBy(server.getDecommissionedBy());
        dto.setDecommissioned(server.isDecommissioned());
        // Deliberately does NOT exclude servers whose decommissioned flag is already set. Decommissioning
        // erases the server now, so a live row can only carry that flag if it was marked under the
        // previous marker-only behaviour -- excluding those would leave them permanently stuck: flagged,
        // still present, and with no action available to actually clear them out.
        dto.setDecommissionReady(allFinalDeltasComplete(combinations));

        if (includePairs) {
            dto.setPairs(pairs.stream().map(WorkspacePairDto::fromEntity).toList());
        }

        return dto;
    }

    private boolean sameCombination(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }
}
