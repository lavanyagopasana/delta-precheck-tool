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
    private Long serverId;
    private SubmissionStatus status;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String startedByEmail;
    private boolean lockedByOther;
    private int completedCount;
    private int totalCount;
    private List<PreCheckItemDto> items;
}
