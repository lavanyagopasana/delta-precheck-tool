package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.MetabaseStatusDto;
import com.cloudfuze.deltatracker.entity.PreCheckItem;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.PreCheckItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Captures a checklist item's evidence straight from Metabase, so an engineer records the figures by
 * asking for them rather than by screenshotting the Metabase UI and uploading the image.
 *
 * <h2>Why not keep uploading screenshots</h2>
 *
 * A screenshot is a photograph of a number. It cannot be re-run, it cannot be compared against the
 * same query at approval time, and it is stored on a filesystem that nothing backs up -- on
 * 2026-08-29 six of them were destroyed beyond recovery when a project was deleted, because
 * {@code ServerPurgeService} unlinks evidence files and the pre-deploy backup is a {@code pg_dump}
 * that contains no files at all. What this writes instead lives in a column, and every deploy dumps
 * the database.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * It does not set the item's status. The checklist exists so that a named person asserts readiness;
 * if a query decided that, the three-role sign-off would be certifying an aggregation rather than a
 * judgement. Capture fills in the evidence and leaves status and note to the engineer -- which is
 * also all this was ever asked to remove from their work.
 *
 * <h2>Scope</h2>
 *
 * Figures are per product-type DATABASE, exactly what {@code MetabaseStatusService.statusForProject}
 * already produces and what the Migration process status panel shows. Narrowing to the specific
 * combination needs a way to map a {@code WorkspacePair} to Metabase's {@code moveWorkSpaceId}, which
 * does not exist yet -- when it does, only the query behind this changes, not the stored shape.
 *
 * <p>Reuses that service rather than issuing its own aggregation on purpose: a second query here
 * could disagree with the panel an engineer checked the numbers against, and "the tool showed me two
 * different figures" is worse than no automation.
 */
@Service
public class PreCheckEvidenceCaptureService {

    /** Marks where a stored blob came from, so a later source can be told apart from this one. */
    static final String SOURCE_METABASE = "metabase";

    private final PreCheckItemRepository preCheckItemRepository;
    private final MetabaseStatusService metabaseStatusService;
    private final ObjectMapper objectMapper;

    public PreCheckEvidenceCaptureService(PreCheckItemRepository preCheckItemRepository,
                                           MetabaseStatusService metabaseStatusService,
                                           ObjectMapper objectMapper) {
        this.preCheckItemRepository = preCheckItemRepository;
        this.metabaseStatusService = metabaseStatusService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PreCheckItem capture(Long combinationId, Long itemId, String callerEmail) {
        PreCheckItem item = preCheckItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Pre-check item not found: " + itemId));
        // Checked rather than trusted: the item id comes from the URL, and without this an item from
        // another combination could be written through this combination's route.
        if (!Objects.equals(item.getCombinationId(), combinationId)) {
            throw new ResourceNotFoundException("Pre-check item not found: " + itemId);
        }

        Server server = item.getCombination() != null ? item.getCombination().getServer() : null;
        Project project = server != null ? server.getProject() : null;
        if (project == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This item's server isn't attached to a project, so there's no Metabase database to read.");
        }
        ProductType productType = server.getProductType();
        if (productType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Set this server's product type before capturing evidence -- it decides which "
                            + "Metabase collection the figures come from.");
        }

        MetabaseStatusDto status = statusFor(project.getId(), productType);
        // A reachable Metabase that returned an error for this database is NOT evidence. Storing it
        // would leave an item looking evidenced while holding a failure message.
        if (StringUtils.hasText(status.getError())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, status.getError());
        }

        item.setEvidenceData(toJson(status, callerEmail));
        item.setEvidenceCapturedAt(LocalDateTime.now());
        item.setLastModifiedBy(callerEmail);
        item.setLastModifiedAt(LocalDateTime.now());
        return preCheckItemRepository.save(item);
    }

    private MetabaseStatusDto statusFor(Long projectId, ProductType productType) {
        List<MetabaseStatusDto> all = metabaseStatusService.statusForProject(projectId);
        return all.stream()
                .filter(d -> productType.name().equals(d.getProductType()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No Metabase database is set for this project's " + productType
                                + " servers. Set it on the project page, then capture again."));
    }

    /**
     * The stored blob. Wraps the status figures with who captured them and when, so the row is
     * self-describing -- a reader does not need this class to interpret it, which matters because
     * this text outlives any particular version of the code that wrote it.
     */
    private String toJson(MetabaseStatusDto status, String callerEmail) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", SOURCE_METABASE);
        root.put("capturedBy", callerEmail != null ? callerEmail : "unknown");
        root.put("capturedAt", LocalDateTime.now().toString());
        // "database" rather than the whole project: an approver checking these figures needs to know
        // which Metabase database they came from to reproduce them.
        root.put("productType", status.getProductType());
        root.put("databaseName", status.getDatabaseName());
        root.put("collection", status.getCollection());
        root.set("status", objectMapper.valueToTree(status));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Nothing in the DTO is unserialisable, so this is a programming error rather than a
            // condition to degrade for -- but it must not surface as a raw 500 with a Jackson trace.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Captured the figures but couldn't store them. Please try again.");
        }
    }
}
