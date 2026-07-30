package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final ServerRepository serverRepository;
    private final SignOffRepository signOffRepository;

    public DashboardService(ServerRepository serverRepository, SignOffRepository signOffRepository) {
        this.serverRepository = serverRepository;
        this.signOffRepository = signOffRepository;
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
        // Prefetch all sign-offs once and group by server (one row per server+role), instead of two
        // findByServerIdAndRole queries per server. Same values -- purely fewer round-trips.
        Map<Long, EnumMap<SignOffRole, SignOffStatus>> statusByServer = new HashMap<>();
        for (SignOff so : signOffRepository.findAll()) {
            statusByServer
                    .computeIfAbsent(so.getServer().getId(), k -> new EnumMap<>(SignOffRole.class))
                    .put(so.getRole(), so.getStatus());
        }

        for (Server server : serverRepository.findAll()) {
            EnumMap<SignOffRole, SignOffStatus> roles =
                    statusByServer.getOrDefault(server.getId(), new EnumMap<>(SignOffRole.class));
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
        }

        DashboardSummaryDto dto = new DashboardSummaryDto();
        dto.setTotalApprovalRequests(migrationManagerPending + devPending);
        dto.setDevApprovalsDone(devDone);
        dto.setDevApprovalsPending(devPending);
        dto.setMigrationManagerApprovalsDone(migrationManagerDone);
        dto.setMigrationManagerApprovalsPending(migrationManagerPending);
        return dto;
    }
}
