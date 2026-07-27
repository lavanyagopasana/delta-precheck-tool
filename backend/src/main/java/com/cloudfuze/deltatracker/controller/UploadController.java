package com.cloudfuze.deltatracker.controller;

import com.cloudfuze.deltatracker.dto.UploadResponse;
import com.cloudfuze.deltatracker.service.FileStorageService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final FileStorageService fileStorageService;

    public UploadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping
    public UploadResponse upload(@RequestParam MultipartFile file) {
        String path = fileStorageService.store(file);
        String cleanedName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        return new UploadResponse(path, cleanedName);
    }
}
