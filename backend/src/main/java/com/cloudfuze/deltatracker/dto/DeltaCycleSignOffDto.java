package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.DeltaCycleSignOff;
import com.cloudfuze.deltatracker.entity.SignOffRole;
import com.cloudfuze.deltatracker.entity.SignOffStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// One role's frozen approval outcome inside a past cycle's snapshot.
@Getter
@Setter
public class DeltaCycleSignOffDto {

    private SignOffRole role;
    // Pre-resolved server-side so the frontend doesn't duplicate SignOffRole.label()'s mapping.
    private String roleLabel;
    private SignOffStatus status;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private Boolean qaRequired;
    private String declineReason;

    public static DeltaCycleSignOffDto fromEntity(DeltaCycleSignOff signOff) {
        DeltaCycleSignOffDto dto = new DeltaCycleSignOffDto();
        dto.setRole(signOff.getRole());
        dto.setRoleLabel(signOff.getRole().label());
        dto.setStatus(signOff.getStatus());
        dto.setApprovedBy(signOff.getApprovedBy());
        dto.setApprovedAt(signOff.getApprovedAt());
        dto.setQaRequired(signOff.getQaRequired());
        dto.setDeclineReason(signOff.getDeclineReason());
        return dto;
    }
}
