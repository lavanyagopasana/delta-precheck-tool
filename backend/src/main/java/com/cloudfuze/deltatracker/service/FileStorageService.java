package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path uploadRoot;

    public FileStorageService(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not initialize upload directory");
        }
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex);
        }
        String storedName = UUID.randomUUID() + extension;
        try {
            Path target = uploadRoot.resolve(storedName);
            Files.copy(file.getInputStream(), target);
            return "/uploads/" + storedName;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file");
        }
    }

    // Best-effort removal of an evidence file when its owning record is deleted (e.g. a project
    // cascade-delete). Stored paths look like "/uploads/<uuid>.<ext>"; a missing or locked file must
    // never block deleting the DB record, so any failure here is swallowed rather than thrown.
    public void delete(String publicPath) {
        if (!StringUtils.hasText(publicPath)) {
            return;
        }
        String fileName = publicPath.startsWith("/uploads/")
                ? publicPath.substring("/uploads/".length())
                : publicPath;
        try {
            Files.deleteIfExists(uploadRoot.resolve(fileName).normalize());
        } catch (IOException | RuntimeException ignored) {
            // Non-fatal by design -- see method comment.
        }
    }
}
