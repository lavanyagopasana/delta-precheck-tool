package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DashboardSummaryDto {

    private long totalApprovalRequests;
    private long devApprovalsPending;
    private long devApprovalsDone;
    private long migrationManagerApprovalsPending;
    private long migrationManagerApprovalsDone;
}
