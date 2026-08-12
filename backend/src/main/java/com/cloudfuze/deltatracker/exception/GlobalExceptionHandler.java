package com.cloudfuze.deltatracker.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Same property Spring uses to enforce the limit, so the message can't claim a size that isn't
    // the one actually being applied.
    private final String maxFileSize;

    public GlobalExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:20MB}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(HttpStatus.BAD_REQUEST, "Malformed request body"));
    }

    // Two users acting on the same row concurrently: whoever flushes second loses the @Version race.
    // Surface a 409 with a reload-and-retry message rather than a generic 500, so the frontend can
    // tell the user their view was stale instead of showing "Something went wrong". Catches both
    // Spring's ObjectOptimisticLockingFailureException (Hibernate flush path) and JPA's own
    // OptimisticLockException (direct EntityManager flush).
    @ExceptionHandler({OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "This was changed by someone else while you were working on it. Reload and try again."));
    }

    // Backstop for constraint violations (duplicate keys, etc.) that slip past an application-level
    // check -- without this, the raw SQL exception message (table/column names included) would be
    // sent straight to the frontend and shown to the user as-is.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT, "That conflicts with an existing record -- please check your input and try again."));
    }

    // Everything from here down to handleGeneric exists because handleGeneric catches Exception:
    // any Spring MVC exception that already carries a correct 4xx status was being caught by it and
    // relabelled "500 Something went wrong. Please try again." A caller could not tell a genuine
    // server fault from their own bad request, and "please try again" is actively wrong advice when
    // retrying the same request can never succeed. Per .claude/rules/api-conventions.md, a clean 4xx
    // story should be told whenever there is one.

    // A missing static resource (Spring 6.1+ throws this instead of just setting 404) was surfacing
    // as a 500 -- confirmed live on the deployed site, where GET /uploads/<any-missing-file> returned
    // the generic 500 envelope. Evidence attachments are served from /uploads/**, so this is the
    // path users actually hit when a file is gone.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(HttpStatus.NOT_FOUND, "Not found."));
    }

    // The worst case of the 4xx-as-500 problem: attaching an over-limit evidence file told the user
    // "Something went wrong. Please try again." with no hint that size was the issue, in the middle
    // of the pre-check flow -- and retrying an identical upload can never work. The limit is read
    // from the same property that enforces it so the two can't drift apart.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "That file is larger than the " + maxFileSize + " limit."));
    }

    // A multipart upload posted without its file part is the caller's mistake, not a server fault.
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "Required file '" + ex.getRequestPartName() + "' is missing."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing."));
    }

    // e.g. /api/projects/notanumber -- a path variable that can't be bound to its target type.
    // Deliberately names only the parameter, never the offending value or the target class, so the
    // response can't be used to probe internals.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'."));
    }

    // Bean-validation failures on @RequestParam/@PathVariable (as opposed to a @RequestBody, which
    // MethodArgumentNotValidException above already covers).
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(HttpStatus.BAD_REQUEST, message));
    }

    // Catches the remaining Spring MVC exceptions that already know their own status -- wrong HTTP
    // method (405), unsupported Content-Type (415), unacceptable Accept (406), and any future
    // addition -- so a new Spring exception type doesn't silently regress to 500. The status is taken
    // from the exception; the message is only ever the standard reason phrase, never ex.getMessage(),
    // which can name handler methods and supported-type lists.
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(body(status, status.getReasonPhrase()));
    }

    // Backstop for anything not explicitly handled above -- never send ex.getMessage() here, it can
    // carry internal details (file paths, class names, driver-level errors) straight to the client.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again."));
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
