package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final SignOffRepository signOffRepository;
    private final ServerRepository serverRepository;
    private final DeltaCycleRepository deltaCycleRepository;

    public DashboardService(WorkspaceCombinationRepository workspaceCombinationRepository,
                             SignOffRepository signOffRepository,
                             ServerRepository serverRepository,
                             DeltaCycleRepository deltaCycleRepository) {
        this.workspaceCombinationRepository = workspaceCombinationRepository;
        this.signOffRepository = signOffRepository;
        this.serverRepository = serverRepository;
        this.deltaCycleRepository = deltaCycleRepository;
    }

    public DashboardSummaryDto getSummary() {
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
            statusByCombination
                    .computeIfAbsent(so.getCombination().getId(), k -> new EnumMap<>(SignOffRole.class))
                    .put(so.getRole(), so.getStatus());
        }

        long finalDeltasComplete = 0;
        long preDeltasInFlight = 0;
        // Grouped per server so the decommission rollup below can ask "are ALL of this server's
        // combinations done" without a query per server.
        Map<Long, List<WorkspaceCombination>> combinationsByServer = new HashMap<>();

        for (WorkspaceCombination combination : workspaceCombinationRepository.findAll()) {
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
        for (Server server : serverRepository.findAll()) {
            if (server.isDecommissioned()) {
                decommissioned++;
                continue;
            }
            List<WorkspaceCombination> combinations = combinationsByServer.getOrDefault(server.getId(), List.of());
            if (!combinations.isEmpty() && combinations.stream().allMatch(WorkspaceCombination::isFinalDeltaComplete)) {
                readyToDecommission++;
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
        dto.setFinalDeltasComplete(finalDeltasComplete);
        dto.setPreDeltasInFlight(preDeltasInFlight);
        // Every recorded cycle that wasn't a final delta -- i.e. how many pre-delta passes the team has
        // actually completed across all combinations.
        dto.setPreDeltaCyclesCompleted(deltaCycleRepository.findAll().stream()
                .filter(cycle -> cycle.getDeltaType() == DeltaType.PRE_DELTA)
                .filter(cycle -> cycle.getStatus() == DeltaCycleStatus.COMPLETED)
                .count());
        return dto;
    }
}
