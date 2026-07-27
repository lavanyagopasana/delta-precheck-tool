package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ItemStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PreCheckItemUpdateRequest {

    private ItemStatus status;
    private String notes;
    private String evidenceFilePath;
    private String evidenceFileName;
    private String updatedBy;
}
