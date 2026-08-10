package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ProjectSummaryDto {

    private Long id;
    private String name;
    private int serverCount;
    // Every WorkspaceCombination across every one of this project's servers -- a server can have
    // several (e.g. Box -> OneDrive and Google Drive -> OneDrive), each an independent migration, so
    // this is a genuinely different number from serverCount, not a duplicate of it.
    private long combinationCount;
    private long totalPairs;
    private long readyServerCount;
    private long notReadyServerCount;
    private long openEscalationCount;
    private List<String> migrationManagers;
    private String migrationManagerName;
    private List<String> engineerEmails;
    private long devApprovalsDone;
    private long devApprovalsPending;
    private long migrationManagerApprovalsDone;
    private long migrationManagerApprovalsPending;
    // Approval chains counted by COMBINATION, not by role-step. One combination has exactly one chain
    // (Migration Manager -> Dev Lead -> QA Lead, see SignOffService.createChainIfAbsent), so these are
    // the numbers a person can actually reconcile against the Approvals page. The four role-step
    // counters above deliberately omit QA_LEAD and count each role separately, which made the
    // dashboard's "Approvals" donut report Pending (0) while a combination sat waiting on QA.
    private long combinationsFullyApproved;
    private long combinationsAwaitingApproval;
    private long combinationsDeclined;
    private LocalDateTime lastPreCheckSubmittedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    // True when the project has servers and every one of them has completed its FINAL Delta -- the
    // whole project's migration is done. Note decommissioning is actioned per server now, so this is a
    // rollup for the Projects list rather than something you can act on directly.
    private boolean decommissionReady;
    // Per-server breakdown of the above: how many servers are eligible right now, and how many an
    // admin has already marked decommissioned.
    private long serversReadyToDecommission;
    private long serversDecommissioned;
}
