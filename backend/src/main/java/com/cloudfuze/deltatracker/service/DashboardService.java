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

import java.util.Optional;

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
        for (Server server : serverRepository.findAll()) {
            Optional<SignOffStatus> mmStatus = signOffRepository
                    .findByServerIdAndRole(server.getId(), SignOffRole.MIGRATION_LEAD)
                    .map(SignOff::getStatus);
            Optional<SignOffStatus> devStatus = signOffRepository
                    .findByServerIdAndRole(server.getId(), SignOffRole.DEV_LEAD)
                    .map(SignOff::getStatus);

            if (mmStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
                migrationManagerDone++;
            } else if (mmStatus.filter(status -> status == SignOffStatus.PENDING).isPresent()) {
                migrationManagerPending++;
            }

            if (devStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
                devDone++;
            } else if (devStatus.filter(status -> status == SignOffStatus.PENDING).isPresent()
                    && mmStatus.filter(status -> status == SignOffStatus.APPROVED).isPresent()) {
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
