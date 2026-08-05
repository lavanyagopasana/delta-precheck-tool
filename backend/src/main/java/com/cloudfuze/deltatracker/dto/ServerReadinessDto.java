package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.ProductType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ServerReadinessDto {

    private Long serverId;
    private String serverName;
    private PairStatus status;
    // One product type per Server URL, shared by every combination under it.
    private ProductType productType;
    private int totalPairs;
    private int readyCount;
    private int notReadyCount;
    private long openEscalationCount;
    private String readinessStatus;
    private String migrationManagerName;
    private Long projectId;
    private String projectName;
    // One of READY / NOT_SUBMITTED / IN_PROGRESS, aggregated across this server's combinations --
    // READY only once the server has at least one combination and every one of them is fully
    // approved. readinessDetail explains the other two, naming whichever combination/role is next.
    // See ProjectService.applyReadinessStage.
    private String readinessStage;
    private String readinessDetail;
    // Each combination's own pre-check/sign-off/Delta lifecycle lives at
    // GET /api/combinations/{id} -- this list is just enough for the UI to pick one.
    private List<CombinationSummaryDto> combinations;
    private List<WorkspacePairDto> pairs;

    public static String computeReadinessStatus(PairStatus status, long openEscalationCount) {
        if (openEscalationCount > 0) {
            return "RED";
        }
        return switch (status) {
            case DELTA_READY -> "GREEN";
            case IN_PROGRESS -> "YELLOW";
            case PENDING -> "RED";
        };
    }
}
