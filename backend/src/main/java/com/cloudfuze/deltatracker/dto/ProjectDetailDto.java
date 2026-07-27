package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProjectDetailDto extends ProjectSummaryDto {

    private List<ServerReadinessDto> servers;
}
