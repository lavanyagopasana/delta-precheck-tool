package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaCycle;
import com.cloudfuze.deltatracker.entity.DeltaCycleStatus;
import com.cloudfuze.deltatracker.entity.DeltaType;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

// One row of a combination's Delta history, with its frozen checklist and sign-off snapshot. Powers
// the Delta History table on the pre-check page.
@Getter
@Setter
public class DeltaCycleDto {

    private Long id;
    private int cycleNumber;
    private DeltaType deltaType;
    // "Pre-Delta 2" / "Final Delta" -- resolved server-side (DeltaType.label) so the numbering rule
    // lives in exactly one place rather than being reimplemented in the frontend.
    private String label;
    private DeltaCycleStatus status;

    private String submittedBy;
    private LocalDateTime submittedAt;
    private LocalDateTime deltaInitiatedAt;
    private String deltaInitiatedBy;
    private LocalDateTime deltaStartedAt;
    private String deltaStartedBy;
    private LocalDateTime deltaFinishedAt;
    private String deltaFinishedBy;

    private List<DeltaCycleSignOffDto> signOffs;
    private List<DeltaCycleItemDto> items;

    // Only set when status == DECLINED -- which role cut the cycle short, who they were, and why.
    private SignOffRole declinedByRole;
    private String declinedByRoleLabel;
    private String declinedBy;
    private String declineReason;

    public static DeltaCycleDto fromEntity(DeltaCycle cycle, List<DeltaCycleSignOffDto> signOffs,
                                            List<DeltaCycleItemDto> items) {
        DeltaCycleDto dto = new DeltaCycleDto();
        dto.setId(cycle.getId());
        dto.setCycleNumber(cycle.getCycleNumber());
        dto.setDeltaType(cycle.getDeltaType());
        dto.setLabel(cycle.getDeltaType().label(cycle.getCycleNumber()));
        dto.setStatus(cycle.getStatus());
        dto.setSubmittedBy(cycle.getSubmittedBy());
        dto.setSubmittedAt(cycle.getSubmittedAt());
        dto.setDeltaInitiatedAt(cycle.getDeltaInitiatedAt());
        dto.setDeltaInitiatedBy(cycle.getDeltaInitiatedBy());
        dto.setDeltaStartedAt(cycle.getDeltaStartedAt());
        dto.setDeltaStartedBy(cycle.getDeltaStartedBy());
        dto.setDeltaFinishedAt(cycle.getDeltaFinishedAt());
        dto.setDeltaFinishedBy(cycle.getDeltaFinishedBy());
        dto.setSignOffs(signOffs);
        dto.setItems(items);
        dto.setDeclinedByRole(cycle.getDeclinedByRole());
        dto.setDeclinedByRoleLabel(cycle.getDeclinedByRole() != null ? cycle.getDeclinedByRole().label() : null);
        dto.setDeclinedBy(cycle.getDeclinedBy());
        dto.setDeclineReason(cycle.getDeclineReason());
        return dto;
    }
}
