package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    // Allowlist derived from what the evidence/attachment feature genuinely needs: screenshots,
    // exported reports (PDF), spreadsheets/CSVs, and the occasional document. Kept deliberately
    // broad for Stage 1 -- the point of observe mode is to LEARN whether real uploads ever fall
    // outside this before it's ever used to reject anything.
    // Deliberately broad: pre-check evidence is whatever proves the item, and the previous list was
    // narrow enough that a screen recording (the most natural proof for "Delta Message Sync works")
    // or a HAR capture got flagged. Anything genuinely unrecognized still only produces a warning
    // while app.upload.enforce-validation is false, so this list is about what we're willing to
    // ENDORSE once enforcement is switched on.
    //
    // Formats that execute script when a browser renders them (html, svg, xhtml, mhtml, js) are
    // deliberately ABSENT and are additionally forced to download by UploadDispositionFilter --
    // /uploads/** is same-origin and permitAll, so an inline-rendered SVG or HTML upload would be
    // stored XSS against every signed-in reviewer. See that filter for the rest of the reasoning.
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // images -- a screenshot is what most evidence actually is
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
            // documents everyone recognises
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt",
            // one archive format, for a bundle of screenshots
            "zip");

    private static final String ACCEPTED_FORMATS_MESSAGE =
            "Accepted formats: images (PNG, JPG, GIF, WEBP, BMP, HEIC), PDF, Word (DOC/DOCX), "
                    + "Excel (XLS/XLSX), PowerPoint (PPT/PPTX), CSV, TXT, and ZIP. Web pages and SVG "
                    + "aren't accepted as evidence -- they can carry scripts; export a PDF or a "
                    + "screenshot instead.";

    private final Path uploadRoot;

    // Stage 1 (observe, default): validation mismatches are logged as warnings but the upload is
    // still accepted. Flip app.upload.enforce-validation=true for Stage 2 once the warning logs
    // confirm they only ever flag genuinely-invalid files.
    private final boolean enforceValidation;

    public FileStorageService(@Value("${app.upload-dir}") String uploadDir,
                              @Value("${app.upload.enforce-validation:false}") boolean enforceValidation) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.enforceValidation = enforceValidation;
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
        // cleanPath strips any path components a crafted filename might carry ("../", absolute
        // paths); the stored name is a random UUID regardless, so the original name never reaches
        // the filesystem -- only its extension is reused, which is validated below.
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex);
        }

        validateType(file, originalName, extension);

        String storedName = UUID.randomUUID() + extension;
        try {
            Path target = uploadRoot.resolve(storedName).normalize();
            // Defense in depth: the resolved target must stay inside the upload root even though
            // storedName is a UUID we control.
            if (!target.startsWith(uploadRoot)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid file path");
            }
            Files.copy(file.getInputStream(), target);
            return "/uploads/" + storedName;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file");
        }
    }

    // Stage 1 rollout: checks the declared extension, declared content type, and actual leading
    // magic bytes against the allowlist. Any mismatch is logged (observe mode) and the upload is
    // still stored -- unless app.upload.enforce-validation is on, in which case it's rejected with a
    // clear 400. Never throws for a merely-unrecognized magic signature; only for a
    // disallowed/spoofed extension when enforcement is enabled.
    private void validateType(MultipartFile file, String originalName, String extension) {
        String ext = extension.startsWith(".") ? extension.substring(1).toLowerCase(Locale.ROOT) : extension.toLowerCase(Locale.ROOT);
        String declaredType = file.getContentType();
        boolean extAllowed = !ext.isEmpty() && ALLOWED_EXTENSIONS.contains(ext);
        String detected = detectMagicType(file);
        boolean magicMismatch = detected != null && !magicMatchesExtension(detected, ext);

        if (!extAllowed || magicMismatch) {
            log.warn("Upload validation flag [{}]: filename='{}', extension='{}', declaredType='{}', "
                            + "detectedMagic='{}', extensionAllowed={}, magicMismatch={}",
                    enforceValidation ? "ENFORCED" : "observe-only",
                    originalName, ext.isEmpty() ? "(none)" : ext,
                    declaredType == null ? "(none)" : declaredType,
                    detected == null ? "(unrecognized)" : detected,
                    extAllowed, magicMismatch);
            if (enforceValidation) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "\"" + originalName + "\" isn't an accepted file type. " + ACCEPTED_FORMATS_MESSAGE);
            }
        }
    }

    // Reads only the first handful of bytes to sniff a well-known signature. Returns null when the
    // header is unreadable or doesn't match anything we recognize (which is NOT treated as invalid).
    private String detectMagicType(MultipartFile file) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            return null;
        }
        if (read <= 0) {
            return null;
        }
        if (startsWith(head, read, 0x89, 0x50, 0x4E, 0x47)) return "png";
        if (startsWith(head, read, 0xFF, 0xD8, 0xFF)) return "jpg";
        if (startsWith(head, read, 0x47, 0x49, 0x46, 0x38)) return "gif";
        if (startsWith(head, read, 0x42, 0x4D)) return "bmp";
        if (startsWith(head, read, 0x25, 0x50, 0x44, 0x46)) return "pdf";
        if (startsWith(head, read, 0x50, 0x4B, 0x03, 0x04)) return "zip"; // also docx/xlsx/pptx
        if (read >= 12
                && startsWith(head, read, 0x52, 0x49, 0x46, 0x46)
                && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50) {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int len, int... prefix) {
        if (len < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // True when a detected signature is consistent with the declared extension. ZIP-container Office
    // formats all sniff as "zip", so those extensions are considered a match for a zip signature.
    private static boolean magicMatchesExtension(String detected, String ext) {
        if (detected.equals("jpg")) {
            return ext.equals("jpg") || ext.equals("jpeg");
        }
        if (detected.equals("zip")) {
            return List.of("zip", "docx", "xlsx", "pptx").contains(ext);
        }
        return detected.equals(ext);
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
