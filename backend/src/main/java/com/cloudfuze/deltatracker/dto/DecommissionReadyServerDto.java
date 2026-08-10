package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// One row of the Dashboard's "Servers To Decommission" list -- enough to identify the server, link
// to it, and show how long it's been sitting ready. Populated by DashboardService.getSummary()
// alongside the count on DashboardSummaryDto, from the same per-server pass.
@Getter
@Setter
public class DecommissionReadyServerDto {

    private Long serverId;
    private String serverName;
    private Long projectId;
    private String projectName;
    private ProductType productType;
    // The latest of its combinations' Final Delta completion timestamps -- i.e. the moment this
    // server actually became ready, not when the dashboard happened to load.
    private LocalDateTime readySince;
}
