package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class PreCheckSubmissionDto {

    private Long id;
    private Long combinationId;
    private String combinationName;
    private Long serverId;
    private String serverName;
    private SubmissionStatus status;
    private String submittedBy;
    private LocalDateTime submittedAt;
    // Who first touched this checklist -- informational only. It used to also gate visibility/edits
    // for everyone else, but the pre-check is now collaborative: any eligible person can view and
    // fill in what's left of a half-filled form (see PreCheckSubmissionService.toDto).
    private String startedByEmail;
    private int completedCount;
    private int totalCount;
    private List<PreCheckItemDto> items;
}
