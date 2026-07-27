package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadResponse {

    private String filePath;
    private String fileName;

    public UploadResponse(String filePath, String fileName) {
        this.filePath = filePath;
        this.fileName = fileName;
    }
}
