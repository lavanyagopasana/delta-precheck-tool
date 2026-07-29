package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ServerReadinessDto {

    private Long serverId;
    private String serverName;
    private PairStatus status;
    private int totalPairs;
    private int readyCount;
    private int notReadyCount;
    private long openEscalationCount;
    private String readinessStatus;
    private LocalDateTime deltaInitiatedAt;
    private String deltaInitiatedBy;
    private LocalDateTime deltaStartedAt;
    private String deltaStartedBy;
    private LocalDateTime deltaFinishedAt;
    private String deltaFinishedBy;
    private String migrationManagerName;
    private Long projectId;
    private String projectName;
    // One of READY / NOT_SUBMITTED / IN_PROGRESS -- READY means the pre-check is submitted and all
    // three roles have approved. readinessDetail explains the other two: "Pre-check isn't submitted
    // yet", or "<Role> not approved yet" naming whichever role is next in the approval sequence.
    private String readinessStage;
    private String readinessDetail;
    // NOT_STARTED / DRAFT / SUBMITTED -- the server's one pre-check submission status, independent
    // of the approval chain (readinessStage above). Used by the frontend to color/label the
    // pre-check button (e.g. "Start" vs "Continue" vs "View").
    private SubmissionStatus submissionStatus;
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
