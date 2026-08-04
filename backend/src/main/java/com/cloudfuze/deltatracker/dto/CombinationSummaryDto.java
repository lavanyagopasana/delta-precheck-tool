package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.SubmissionStatus;
import lombok.Getter;
import lombok.Setter;

// Lightweight entry in ServerReadinessDto.combinations -- just enough for a picker/list UI (e.g.
// labeling the Pre-Check button without a second fetch). Full detail (items, sign-off chain, Delta
// lifecycle) lives at GET /api/combinations/{id}.
@Getter
@Setter
public class CombinationSummaryDto {

    private Long id;
    private String name;
    private int pairCount;
    private PairStatus status;
    private SubmissionStatus submissionStatus;
}
