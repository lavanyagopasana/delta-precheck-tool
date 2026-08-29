package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DashboardServerDto;
import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.dto.DecommissionReadyServerDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final SignOffRepository signOffRepository;
    private final ServerRepository serverRepository;
    private final DeltaCycleRepository deltaCycleRepository;
    // Owns the one definition of "which projects may this caller see" (ProjectService.isVisible).
    // Asking it, rather than re-deriving the rule here, is what keeps every tile agreeing with the
    // Projects page -- and stops a second copy of that rule drifting the way APPROVAL_SEQUENCE did.
    private final ProjectService projectService;

    public DashboardService(WorkspaceCombinationRepository workspaceCombinationRepository,
                             SignOffRepository signOffRepository,
                             ServerRepository serverRepository,
                             DeltaCycleRepository deltaCycleRepository,
                             ProjectService projectService) {
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.signOffRepository = signOffRepository;
        this.serverRepository = serverRepository;
        this.deltaCycleRepository = deltaCycleRepository;
        this.projectService = projectService;
    }

    /**
     * Every figure on the dashboard, scoped to what this caller may actually see.
     *
     * <p>It used to count the whole database whoever asked, so a Migration Manager saw
     * "Pending Approvals: 7" on the dashboard and then two rows on the Approvals page -- the tiles
     * disagreed with every other screen, and the size of another manager's backlog could be read
     * off them. ADMIN, DEV_LEAD and QA_LEAD still see everything: that is what
     * ProjectService.isVisible already grants them, and this adds no rule of its own.
     *
     * <p>callerEmail == null means auth is not configured, in which case everything stays visible --
     * the same way the rest of the app degrades.
     */
    public DashboardSummaryDto getSummary(String callerEmail, AppUserRole callerRole) {
        Set<Long> visibleProjectIds = projectService.visibleProjects(callerEmail, callerRole).stream()
                .map(Project::getId)
                .collect(Collectors.toSet());

        // A server with no project cannot be reached from any project page, so it belongs to no
        // caller's view. Dropping it keeps the tiles consistent with what a click can actually open.
        List<Server> visibleServers = serverRepository.findAll().stream()
                .filter(server -> server.getProject() != null
                        && visibleProjectIds.contains(server.getProject().getId()))
                .toList();
        Set<Long> visibleServerIds = visibleServers.stream().map(Server::getId).collect(Collectors.toSet());

        List<WorkspaceCombination> visibleCombinations = workspaceCombinationRepository.findAll().stream()
                .filter(c -> c.getServer() != null && visibleServerIds.contains(c.getServer().getId()))
                .toList();
        Set<Long> visibleCombinationIds = visibleCombinations.stream()
                .map(WorkspaceCombination::getId)
                .collect(Collectors.toSet());

        long migrationManagerDone = 0;
        long migrationManagerPending = 0;
        long devDone = 0;
        long devPending = 0;

        // Migration Manager is always first in the approval chain, so a PENDING row there is always
        // someone's active turn. Dev Lead's row is created at the same time as Migration Manager's
        // (see SignOffService.createChainIfAbsent), so a PENDING Dev row only reflects a genuinely
        // open request once Migration Manager has actually approved -- otherwise it just hasn't been
        // reached yet and shouldn't be counted as "pending" alongside requests really awaiting action.
        // Prefetch all sign-offs once and group by combination (one row per combination+role),
        // instead of two findByCombinationIdAndRole queries per combination. Counted per combination
        // now, not per server -- a server can have several combinations, each with its own chain.
        Map<Long, EnumMap<SignOffRole, SignOffStatus>> statusByCombination = new HashMap<>();
        for (SignOff so : signOffRepository.findAll()) {
            if (so.getCombination() == null || !visibleCombinationIds.contains(so.getCombination().getId())) {
                continue;
            }
            statusByCombination
                    .computeIfAbsent(so.getCombination().getId(), k -> new EnumMap<>(SignOffRole.class))
                    .put(so.getRole(), so.getStatus());
        }

        long finalDeltasComplete = 0;
        long preDeltasInFlight = 0;
        // Grouped per server so the decommission rollup below can ask "are ALL of this server's
        // combinations done" without a query per server.
        Map<Long, List<WorkspaceCombination>> combinationsByServer = new HashMap<>();

        for (WorkspaceCombination combination : visibleCombinations) {
            EnumMap<SignOffRole, SignOffStatus> roles =
                    statusByCombination.getOrDefault(combination.getId(), new EnumMap<>(SignOffRole.class));
            SignOffStatus mmStatus = roles.get(SignOffRole.MIGRATION_LEAD);
            SignOffStatus devStatus = roles.get(SignOffRole.DEV_LEAD);

            if (mmStatus == SignOffStatus.APPROVED) {
                migrationManagerDone++;
            } else if (mmStatus == SignOffStatus.PENDING) {
                migrationManagerPending++;
            }

            if (devStatus == SignOffStatus.APPROVED) {
                devDone++;
            } else if (devStatus == SignOffStatus.PENDING && mmStatus == SignOffStatus.APPROVED) {
                devPending++;
            }

            if (combination.isFinalDeltaComplete()) {
                finalDeltasComplete++;
            } else if (combination.getCurrentDeltaType() == DeltaType.PRE_DELTA) {
                preDeltasInFlight++;
            }
            combinationsByServer
                    .computeIfAbsent(combination.getServer().getId(), k -> new ArrayList<>())
                    .add(combination);
        }

        // Counted per server rather than per project: decommissioning is a per-server action now. A
        // server with no combinations is never ready -- an empty server has nothing to migrate, so
        // calling it decommissionable would be misleading. Mirrors ServerService.isDecommissionReady.
        long readyToDecommission = 0;
        long decommissioned = 0;
        List<DecommissionReadyServerDto> readyServers = new ArrayList<>();
        for (Server server : visibleServers) {
            if (server.isDecommissioned()) {
                decommissioned++;
                continue;
            }
            List<WorkspaceCombination> combinations = combinationsByServer.getOrDefault(server.getId(), List.of());
            if (!combinations.isEmpty() && combinations.stream().allMatch(WorkspaceCombination::isFinalDeltaComplete)) {
                readyToDecommission++;
                readyServers.add(toDecommissionReadyDto(server, combinations));
            }
        }

        DashboardSummaryDto dto = new DashboardSummaryDto();
        dto.setTotalApprovalRequests(migrationManagerPending + devPending);
        dto.setDevApprovalsDone(devDone);
        dto.setDevApprovalsPending(devPending);
        dto.setMigrationManagerApprovalsDone(migrationManagerDone);
        dto.setMigrationManagerApprovalsPending(migrationManagerPending);
        dto.setServersReadyToDecommission(readyToDecommission);
        dto.setServersDecommissioned(decommissioned);
        // Sorted oldest-ready-first -- the servers that have been waiting longest are the ones most
        // worth acting on first.
        readyServers.sort(Comparator.comparing(DecommissionReadyServerDto::getReadySince,
                Comparator.nullsLast(Comparator.naturalOrder())));
        dto.setDecommissionReadyServers(readyServers);
        dto.setFinalDeltasComplete(finalDeltasComplete);
        dto.setPreDeltasInFlight(preDeltasInFlight);
        // Every recorded cycle that wasn't a final delta -- i.e. how many pre-delta passes the team has
        // actually completed across all combinations.
        dto.setPreDeltaCyclesCompleted(deltaCycleRepository.findAll().stream()
                .filter(cycle -> cycle.getCombination() != null
                        && visibleCombinationIds.contains(cycle.getCombination().getId()))
                .filter(cycle -> cycle.getDeltaType() == DeltaType.PRE_DELTA)
                .filter(cycle -> cycle.getStatus() == DeltaCycleStatus.COMPLETED)
                .count());
        dto.setServers(buildServerList(visibleServers, combinationsByServer));
        return dto;
    }

    /**
     * The rows behind the Servers and Delta Ready tiles. Built from the same scoped lists the counts
     * above were computed from, so a popup can never show a row the number did not count, or omit
     * one it did.
     */
    private List<DashboardServerDto> buildServerList(
            List<Server> servers, Map<Long, List<WorkspaceCombination>> combinationsByServer) {
        List<DashboardServerDto> rows = new ArrayList<>();
        for (Server server : servers) {
            DashboardServerDto dto = new DashboardServerDto();
            dto.setServerId(server.getId());
            dto.setServerName(server.getName());
            dto.setProductType(server.getProductType());
            if (server.getProject() != null) {
                dto.setProjectId(server.getProject().getId());
                dto.setProjectName(server.getProject().getName());
            }
            // Matches ProjectService.buildSummary's readyServerCount exactly -- the tile this list
            // sits under counts servers in this state, so any other rule here would put a popup and
            // the number above it in disagreement.
            List<WorkspaceCombination> own = combinationsByServer.getOrDefault(server.getId(), List.of());
            dto.setCombinations(own.stream()
                    .map(WorkspaceCombination::getName)
                    .filter(Objects::nonNull)
                    .toList());
            dto.setDeltaReady(server.getStatus() == PairStatus.DELTA_READY);
            dto.setDeltaReadyCombinations(combinationsByServer.getOrDefault(server.getId(), List.of()).stream()
                    .filter(c -> c.getStatus() == PairStatus.DELTA_READY)
                    .map(WorkspaceCombination::getName)
                    .filter(Objects::nonNull)
                    .toList());
            rows.add(dto);
        }
        // Grouped by project, then server, so a long list reads as projects rather than as an
        // undifferentiated wall of server names.
        rows.sort(Comparator
                .comparing(DashboardServerDto::getProjectName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DashboardServerDto::getServerName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    // The server became ready the moment its LAST combination finished its Final Delta, not its
    // first -- readySince is the latest of the per-combination timestamps, matching the "every
    // combination done" condition the caller already checked before calling this.
    private DecommissionReadyServerDto toDecommissionReadyDto(Server server, List<WorkspaceCombination> combinations) {
        DecommissionReadyServerDto dto = new DecommissionReadyServerDto();
        dto.setServerId(server.getId());
        dto.setServerName(server.getName());
        dto.setProductType(server.getProductType());
        if (server.getProject() != null) {
            dto.setProjectId(server.getProject().getId());
            dto.setProjectName(server.getProject().getName());
        }
        combinations.stream()
                .map(WorkspaceCombination::getFinalDeltaCompletedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .ifPresent(dto::setReadySince);
        return dto;
    }
}
