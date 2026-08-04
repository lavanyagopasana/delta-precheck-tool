package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.DashboardSummaryDto;
import com.cloudfuze.deltatracker.entity.SignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final WorkspaceCombinationRepository workspaceCombinationRepository;
    private final SignOffRepository signOffRepository;

    public DashboardService(WorkspaceCombinationRepository workspaceCombinationRepository, SignOffRepository signOffRepository) {
        this.workspaceCombinationRepository = workspaceCombinationRepository;
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
        // Prefetch all sign-offs once and group by combination (one row per combination+role),
        // instead of two findByCombinationIdAndRole queries per combination. Counted per combination
        // now, not per server -- a server can have several combinations, each with its own chain.
        Map<Long, EnumMap<SignOffRole, SignOffStatus>> statusByCombination = new HashMap<>();
        for (SignOff so : signOffRepository.findAll()) {
            statusByCombination
                    .computeIfAbsent(so.getCombination().getId(), k -> new EnumMap<>(SignOffRole.class))
                    .put(so.getRole(), so.getStatus());
        }

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
