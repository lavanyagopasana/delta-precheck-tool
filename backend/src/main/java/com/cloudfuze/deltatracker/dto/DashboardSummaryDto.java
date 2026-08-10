package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DashboardSummaryDto {

    private long totalApprovalRequests;
    private long devApprovalsPending;
    private long devApprovalsDone;
    private long migrationManagerApprovalsPending;
    private long migrationManagerApprovalsDone;

    // --- Multi-cycle Delta / decommission rollups ---
    // Servers eligible for decommissioning right now (every combination's Final Delta done, not yet
    // marked decommissioned). Counted per SERVER, not per project: decommissioning is a per-server
    // action now, and the dashboard tile previously derived this client-side from the projects list.
    private long serversReadyToDecommission;
    private long serversDecommissioned;
    // The actual servers behind the count above -- backs the Dashboard's "Servers To Decommission"
    // list, so clicking the tile has somewhere real to jump to instead of just a number.
    private List<DecommissionReadyServerDto> decommissionReadyServers;
    // Combinations that have completed their Final Delta -- migrations fully finished.
    private long finalDeltasComplete;
    // Pre-delta cycles recorded so far across every combination, and how many combinations are
    // currently mid-cycle on a pre-delta.
    private long preDeltaCyclesCompleted;
    private long preDeltasInFlight;
}
