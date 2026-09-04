package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.MetabaseDatabaseDto;
import com.cloudfuze.deltatracker.dto.MetabaseStatusCountDto;
import com.cloudfuze.deltatracker.dto.MetabaseStatusDto;
import com.cloudfuze.deltatracker.dto.MetabaseUserDto;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.ProjectMetabaseDatabase;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.ProjectMetabaseDatabaseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a project's fixed Metabase database names into the per-product-type {@code processStatus}
 * breakdown the project page shows.
 *
 * <p>Reproduces, per product type, the Metabase UI flow: filter {@code OwnerEmailId} to the customer,
 * Summarize &gt; Count, Group by {@code ProcessStatus}. Verified 2026-08-27 against db 195
 * ({@code morrisanimal}) to return exactly the 8 rows and 6987 total that screen shows.
 *
 * <p><b>Nothing here converts a failure into a zero.</b> An unset database, an unreachable Metabase, an
 * unknown database name and a genuinely empty collection are four different things, and collapsing any
 * of them into "0 processed" would read as "no migration has happened" -- the worst possible wrong
 * answer for a figure a Delta gets approved against. Each comes back as its own state on
 * {@link MetabaseStatusDto}.
 */
@Service
public class MetabaseStatusService {

    /** Which collection carries the counts, per product type. See the class comment for provenance. */
    private static final Map<ProductType, String> COLLECTION_BY_PRODUCT_TYPE = Map.of(
            ProductType.MESSAGE, "MessageWorkSpace",
            ProductType.CONTENT, "MoveWorkSpaces",
            ProductType.EMAIL, "emailWorkSpace");

    /**
     * Display order for the status rows. Deliberately covers all three product types' vocabularies at
     * once -- email says PROCESSED_WITH_CONFLICTS / PROCESSED_WITH_FOLDER_CONFLICT / PAUSE where
     * message says PROCESSED_WITH_SOME_CONFLICTS / SUSPENDED. Anything NOT in this list still renders,
     * sorted after these by descending count: a status nobody has seen before must reach the screen,
     * because a dropped row understates conflicts.
     */
    private static final List<String> STATUS_ORDER = List.of(
            "PROCESSED",
            // Drive changes only -- their vocabulary is PROCESSED / COMPLETED / NOT_PROCESSED, and
            // COMPLETED never appears on a workspace row. Listed here because both breakdowns are
            // ordered by this one list.
            "COMPLETED",
            "PROCESSED_WITH_SOME_CONFLICTS",
            "PROCESSED_WITH_CONFLICTS",
            "PROCESSED_WITH_FOLDER_CONFLICT",
            "CONFLICT",
            "IN_PROGRESS",
            "NOT_PROCESSED",
            "NO_MESSAGE",
            "SUSPENDED",
            "PAUSE",
            "CANCEL");

    private final ProjectMetabaseDatabaseRepository projectMetabaseDatabaseRepository;
    private final MetabaseClient metabaseClient;

    public MetabaseStatusService(ProjectMetabaseDatabaseRepository projectMetabaseDatabaseRepository,
                                  MetabaseClient metabaseClient) {
        this.projectMetabaseDatabaseRepository = projectMetabaseDatabaseRepository;
        this.metabaseClient = metabaseClient;
    }

    /**
     * One entry per database this project has added -- several per product type is normal. Returns an
     * empty list rather
     * than throwing when none is set -- "nobody has chosen a database yet" is a normal state of a new
     * project, not an error.
     */
    public List<MetabaseStatusDto> statusForProject(Long projectId) {
        List<ProjectMetabaseDatabase> configured = projectMetabaseDatabaseRepository.findByProjectId(projectId);
        List<MetabaseStatusDto> out = new ArrayList<>();
        // Resolved once and reused: the database list is one call, and a project with three product
        // types would otherwise fetch all 159 databases three times.
        List<MetabaseDatabaseDto> databases = null;
        String lookupError = null;
        if (!configured.isEmpty()) {
            try {
                databases = metabaseClient.fetchDatabases();
            } catch (ApiException e) {
                lookupError = e.getMessage();
            }
        }
        for (ProjectMetabaseDatabase row : configured) {
            out.add(buildOne(row, databases, lookupError));
        }
        // By name as well as type: a product type can be spread across several databases, and
        // without the tiebreaker two entries for the same type would swap places between loads.
        out.sort(Comparator.comparing(MetabaseStatusDto::getProductType)
                .thenComparing(MetabaseStatusDto::getDatabaseName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return out;
    }

    private MetabaseStatusDto buildOne(ProjectMetabaseDatabase row, List<MetabaseDatabaseDto> databases,
                                        String lookupError) {
        MetabaseStatusDto dto = new MetabaseStatusDto();
        dto.setProductType(row.getProductType().name());
        dto.setDatabaseName(row.getDatabaseName());
        dto.setCollection(COLLECTION_BY_PRODUCT_TYPE.get(row.getProductType()));
        dto.setStatuses(List.of());
        dto.setOwnerEmails(List.of());

        if (lookupError != null) {
            dto.setError(lookupError);
            return dto;
        }

        Optional<MetabaseDatabaseDto> match = databases.stream()
                .filter(db -> db.getName() != null && db.getName().equalsIgnoreCase(row.getDatabaseName()))
                .findFirst();
        if (match.isEmpty() || match.get().getId() == null) {
            // Named a database Metabase doesn't list. Usually renamed or deleted there, or this account
            // lost permission to it -- all of which a human has to fix, so say so rather than show zero.
            dto.setError("Metabase has no database called \"" + row.getDatabaseName()
                    + "\" -- it may have been renamed, or this account can't see it.");
            return dto;
        }

        long databaseId = match.get().getId();
        String collection = dto.getCollection();
        try {
            Map<String, Long> counts = metabaseClient.countByProcessStatus(databaseId, collection);
            dto.setStatuses(order(counts));
            dto.setTotalWorkspaces(counts.values().stream().mapToLong(Long::longValue).sum());

            Map<String, Long> owners = metabaseClient.countByOwnerEmail(databaseId, collection);
            dto.setOwnerEmails(owners.keySet().stream()
                    .filter(email -> email != null && !isCloudFuzeInternal(email))
                    .sorted()
                    .toList());
            dto.setExcludedInternalWorkspaces(owners.entrySet().stream()
                    .filter(e -> e.getKey() != null && isCloudFuzeInternal(e.getKey()))
                    .mapToLong(Map.Entry::getValue)
                    .sum());
        } catch (ApiException e) {
            dto.setError(e.getMessage());
        }

        if (row.getProductType() == ProductType.CONTENT) {
            addDriveChanges(dto, databaseId);
        }
        return dto;
    }

    /**
     * The Drive change breakdown, which is the flow: read the customer's user ids out of
     * {@code Users}, filter {@code DriveChangeIdDetails} to them, then count by {@code status}.
     *
     * <p>Two collections rather than one because a Drive change row carries only a {@code userId} --
     * no email -- so the @cloudfuze.com split every other figure makes directly can only be made
     * here by resolving ids first.
     *
     * <p>In its own try/catch, and writing to its own error field, so that a Drive-change failure
     * leaves the workspace counts on screen. They are independent reads and one being unavailable is
     * no reason to hide the other.
     *
     * <p><b>No customer users is reported, not counted as zero.</b> Filtering on an empty id list
     * would match nothing and render as "0 Drive changes", which reads as "nothing migrated" when
     * the truth is that this database has no non-CloudFuze user to attribute changes to.
     */
    private void addDriveChanges(MetabaseStatusDto dto, long databaseId) {
        try {
            Map<String, String> customerUsers = metabaseClient.customerUserIds(databaseId);
            if (customerUsers.isEmpty()) {
                dto.setDriveChangesError("This database has no non-CloudFuze user, so Drive changes "
                        + "can't be attributed to the customer.");
                return;
            }
            dto.setDriveChangeUsers(customerUsers.entrySet().stream()
                    .map(e -> new MetabaseUserDto(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(MetabaseUserDto::getEmail,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList());
            Map<String, Long> counts = metabaseClient.countDriveChangesByStatus(databaseId, customerUsers.keySet());
            dto.setDriveChanges(order(counts));
            long counted = counts.values().stream().mapToLong(Long::longValue).sum();
            // No excluded-internal figure here, unlike the workspace counts. It was a large noisy
            // number nobody acted on -- 10,085 excluded against 73 counted on a real project -- and
            // it read as though something had gone wrong with the figures above it. Dropping it also
            // drops a whole-collection COUNT per content database from an endpoint already taking
            // ~15s, against a collection that can hold millions of rows.
            dto.setTotalDriveChanges(counted);
        } catch (ApiException e) {
            dto.setDriveChangesError(e.getMessage());
        }
    }

    /**
     * The @cloudfuze.com DOMAIN, not the word "cloudfuze" anywhere. {@code cloudfuze@azaleawang.com} is
     * a CloudFuze-operated account on a CUSTOMER's domain and its rows are genuine customer data -- a
     * substring match would silently drop that whole project's figures.
     */
    static boolean isCloudFuzeInternal(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).trim().endsWith("@cloudfuze.com");
    }

    /** Known statuses first in STATUS_ORDER, then anything unrecognised by descending count. */
    private List<MetabaseStatusCountDto> order(Map<String, Long> counts) {
        List<MetabaseStatusCountDto> known = new ArrayList<>();
        List<MetabaseStatusCountDto> unknown = new ArrayList<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            // A document with no processStatus at all still has to be visible, so it is labelled rather
            // than skipped.
            String status = entry.getKey() == null ? "(no status)" : entry.getKey();
            MetabaseStatusCountDto dto = new MetabaseStatusCountDto(status, entry.getValue());
            (STATUS_ORDER.contains(status) ? known : unknown).add(dto);
        }
        known.sort(Comparator.comparingInt(d -> STATUS_ORDER.indexOf(d.getStatus())));
        unknown.sort(Comparator.comparingLong(MetabaseStatusCountDto::getCount).reversed());
        known.addAll(unknown);
        return known;
    }
}
