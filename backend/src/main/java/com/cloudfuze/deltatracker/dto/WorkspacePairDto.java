package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.WorkspacePair;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspacePairDto {

    private Long id;
    private Long serverId;
    private String serverName;
    private String sourceEmail;
    private String sourcePath;
    private String destinationEmail;
    private String destinationPath;
    private String combination;

    public static WorkspacePairDto fromEntity(WorkspacePair pair) {
        WorkspacePairDto dto = new WorkspacePairDto();
        dto.setId(pair.getId());
        dto.setServerId(pair.getServer().getId());
        dto.setServerName(pair.getServer().getName());
        dto.setSourceEmail(pair.getSourceEmail());
        dto.setSourcePath(pair.getSourcePath());
        dto.setDestinationEmail(pair.getDestinationEmail());
        dto.setDestinationPath(pair.getDestinationPath());
        dto.setCombination(pair.getCombination());
        return dto;
    }
}
