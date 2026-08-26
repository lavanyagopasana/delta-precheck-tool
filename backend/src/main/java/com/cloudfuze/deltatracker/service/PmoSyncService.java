package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.dto.PmoSyncResultDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors the PMO tool's project list into this tracker so a project created there shows up here
 * without anybody re-typing it.
 *
 * <p><b>This is a one-way pull, and deliberately not a full mirror.</b> PMO is the authority on which
 * projects exist and what they are called; this tool stays the authority on everything it owns
 * (servers, workspace pairs, checklists, sign-off chains, delta cycles). So the sync only ever creates
 * a project or refreshes its display fields -- it never deletes and never touches assignments.
 *
 * <p>Three decisions here that look arbitrary but are not:
 * <ul>
 *   <li><b>Matching is by {@code externalId}, never by name.</b> PMO's UUID is the only stable key. A
 *       project renamed in PMO must update the row we already have; matching by name would instead
 *       create a second project and orphan the original along with all its migration history.</li>
 *   <li><b>Names are disambiguated on import, because PMO's are not unique.</b> Of 190 records, 152
 *       names are distinct -- the same customer appears several times split by migration type
 *       ({@code akira} is three projects: Drive, Gmail, Chat). {@code Project.name} is {@code UNIQUE}
 *       in this database, so colliding records get their migration type appended
 *       ({@code akira (Gmail - Gmail)}). Names that do not collide are left exactly as PMO has them.</li>
 *   <li><b>PMO's project manager becomes the Migration Manager, but only via a resolved email.</b>
 *       PMO reports a display name ({@code Harika}); {@code migrationManagerName} is compared as an
 *       email address by {@code ProjectService.isVisible} and by the entire sign-off chain, so the
 *       raw display name must never be written there. {@link #resolveManagerEmail} maps it to a real
 *       {@code MIGRATION_MANAGER} account, and only an unambiguous match is assigned.</li>
 * </ul>
 *
 * <p><b>Consequence worth knowing:</b> a project whose PMO manager cannot be resolved arrives with no
 * Migration Manager, which means (a) {@code ProjectService.isVisible} shows it only to
 * ADMIN/DEV_LEAD/QA_LEAD, and (b) {@code PreCheckSubmissionService.submit} refuses submission, until
 * an admin assigns one. Against the live roster on 2026-08-26 that applied to three of PMO's eleven
 * managers: two ({@code Sriram Ramakrishnan}, {@code Chandra Mouli}) exist here as
 * MIGRATION_ENGINEERs rather than managers, and one ({@code Nivas}) has no account at all. Those are
 * reported in {@code PmoSyncResultDto.unresolvedManagers} rather than papered over.
 */
@Service
public class PmoSyncService {

    private static final Logger log = LoggerFactory.getLogger(PmoSyncService.class);

    /** Project.name is a plain unique column (Hibernate default length 255). */
    private static final int MAX_NAME_LENGTH = 255;

    /**
     * Stamped on createdBy for synced projects so the Projects page shows where they came from.
     * Deliberately not an email address: createdBy is compared against the caller's email by
     * ProjectService.isVisible, and a real-looking address here could accidentally grant somebody
     * visibility of every synced project.
     */
    static final String SYNC_CREATED_BY = "PMO sync";

    /**
     * Stops the admin-triggered sync and the scheduled poll running at the same time. Without it both
     * see findByExternalId() return empty for the same PMO project, both try to create it, and the
     * UNIQUE constraint on external_id turns the loser into up to 79 reported errors -- alarming, but
     * describing a collision that only happened because we raced ourselves.
     *
     * <p>Per-JVM, not cluster-wide. That is sufficient here (one backend container) and the DB
     * constraint remains the real guarantee; this only stops us generating our own noise.
     */
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    private final PmoProjectClient pmoProjectClient;
    private final ProjectRepository projectRepository;
    private final AppUserService appUserService;

    /**
     * Which PMO statuses to import, comma-separated. Defaults to ACTIVE only, by product decision on
     * 2026-08-26: of 190 PMO projects, 105 are COMPLETED and 1 CANCELLED -- finished work that can
     * never need a pre-check, so importing it would put permanent noise in every list and count on the
     * Projects page. Add ON_HOLD (5 projects) or COMPLETED here if that judgement changes; a project
     * that later flips to ACTIVE in PMO is picked up by the next poll, so nothing is lost by excluding it.
     */
    @Value("${pmo.import-statuses:ACTIVE}")
    private String importStatuses;

    @Value("${pmo.auto-sync-enabled:true}")
    private boolean autoSyncEnabled;

    public PmoSyncService(PmoProjectClient pmoProjectClient, ProjectRepository projectRepository,
                           AppUserService appUserService) {
        this.pmoProjectClient = pmoProjectClient;
        this.projectRepository = projectRepository;
        this.appUserService = appUserService;
    }

    /**
     * Fetches PMO's list and upserts it. Not transactional at the method level, for the same reason as
     * {@code TicketService.syncOpenTicketsFromTracker}: the fetch is a slow external call and the loop
     * that follows writes up to a couple of hundred rows, so one surrounding transaction would hold a
     * DB connection open across the whole round trip. Each row's own save uses Spring Data's
     * per-call transaction, and one bad record is collected as an error rather than aborting the batch.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PmoSyncResultDto sync() {
        if (!syncInProgress.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A PMO sync is already running -- try again in a moment.");
        }
        try {
            return runSync();
        } finally {
            syncInProgress.set(false);
        }
    }

    private PmoSyncResultDto runSync() {
        List<PmoProjectDto> all = pmoProjectClient.fetchProjects();
        PmoSyncResultDto result = new PmoSyncResultDto();

        Set<String> wanted = parseStatuses(importStatuses);
        List<PmoProjectDto> importable = new ArrayList<>();
        for (PmoProjectDto record : all) {
            if (!wanted.isEmpty() && (record.getStatus() == null
                    || !wanted.contains(record.getStatus().trim().toUpperCase(Locale.ROOT)))) {
                result.setSkippedByStatusCount(result.getSkippedByStatusCount() + 1);
                continue;
            }
            if (!StringUtils.hasText(record.getExternalId())) {
                result.addError("Skipped a PMO record with no id: " + record.getName());
                continue;
            }
            if (!StringUtils.hasText(record.getName())) {
                result.addError("Skipped PMO project " + record.getExternalId() + ": it has no name.");
                continue;
            }
            importable.add(record);
        }
        result.setTotalRows(importable.size());

        Map<String, String> nameByExternalId = assignNames(importable);
        // Built once per run, not per record: the roster read is cached but the index itself is pure
        // string work, and there are ~80 records to get through.
        ManagerIndex managers = buildManagerIndex();
        Set<String> unresolved = new LinkedHashSet<>();

        for (PmoProjectDto record : importable) {
            try {
                upsert(record, nameByExternalId.get(record.getExternalId()), managers, unresolved, result);
            } catch (Exception e) {
                // One unusable record must not cost us the other 78.
                result.addError("Could not sync \"" + record.getName() + "\" (" + record.getExternalId()
                        + "): " + e.getMessage());
            }
        }
        result.setUnresolvedManagers(new ArrayList<>(unresolved));
        return result;
    }

    /**
     * Background poll, so a project created in PMO turns up here on its own. Five minutes rather than
     * TicketService's fifteen because "it should appear straight away" is the whole point of this
     * feature; PMO's API is one cheap ~100KB GET, so the extra frequency costs little.
     *
     * <p>There is no push/webhook option -- PMO offers a pull API only -- so five minutes is as close
     * to instant as this can get without PMO calling us.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Scheduled(fixedDelayString = "${pmo.sync-interval-ms:300000}",
               initialDelayString = "${pmo.sync-initial-delay-ms:60000}")
    public void scheduledSync() {
        if (!autoSyncEnabled || !pmoProjectClient.isConfigured()) {
            return;
        }
        // An admin-triggered sync is mid-flight; it is doing exactly this work already.
        if (syncInProgress.get()) {
            return;
        }
        try {
            PmoSyncResultDto result = sync();
            // Only worth a line in the log when something actually changed; an unchanged poll every
            // five minutes would bury everything else.
            if (result.getCreatedCount() > 0 || result.getUpdatedCount() > 0 || !result.getErrors().isEmpty()) {
                log.info("PMO sync: {} created, {} updated, {} unchanged, {} skipped by status, {} error(s).",
                        result.getCreatedCount(), result.getUpdatedCount(), result.getUnchangedCount(),
                        result.getSkippedByStatusCount(), result.getErrors().size());
                result.getErrors().forEach(e -> log.warn("PMO sync: {}", e));
            }
        } catch (Exception e) {
            log.warn("PMO sync failed: {}", e.toString());
        }
    }

    /**
     * Works out the name each PMO record should have here, resolving PMO's own duplicates. A name that
     * appears once is used as-is (trimmed); one that repeats gets its migration type appended, which
     * separates 30 of the 31 duplicate groups in the live feed. The last group is identical in both
     * name and migration type, so it falls back to a short id suffix -- ugly, but unique and stable,
     * and it beats dropping a live project.
     */
    private Map<String, String> assignNames(List<PmoProjectDto> records) {
        Map<String, List<PmoProjectDto>> byName = new LinkedHashMap<>();
        for (PmoProjectDto record : records) {
            byName.computeIfAbsent(normalize(record.getName()), k -> new ArrayList<>()).add(record);
        }

        Map<String, String> assigned = new LinkedHashMap<>();
        for (List<PmoProjectDto> group : byName.values()) {
            if (group.size() == 1) {
                PmoProjectDto only = group.get(0);
                assigned.put(only.getExternalId(), truncate(only.getName().trim()));
                continue;
            }
            Set<String> usedInGroup = new HashSet<>();
            for (PmoProjectDto record : group) {
                String base = record.getName().trim();
                String candidate = StringUtils.hasText(record.getMigrationTypes())
                        ? base + " (" + record.getMigrationTypes().trim() + ")"
                        : base;
                candidate = truncate(candidate);
                if (!usedInGroup.add(normalize(candidate))) {
                    candidate = truncate(base + " (" + shortId(record.getExternalId()) + ")");
                    usedInGroup.add(normalize(candidate));
                }
                assigned.put(record.getExternalId(), candidate);
            }
        }
        return assigned;
    }

    private void upsert(PmoProjectDto record, String desiredName, ManagerIndex managers,
                         Set<String> unresolved, PmoSyncResultDto result) {
        Optional<Project> existing = projectRepository.findByExternalId(record.getExternalId());
        Project project = existing.orElseGet(Project::new);
        boolean isNew = existing.isEmpty();

        String name = resolveAgainstExistingNames(desiredName, record, project);

        // Capture before-state so an unchanged poll reports "unchanged" instead of claiming an update.
        String before = signature(project);
        // Read before the incoming values overwrite it: applyManager needs to know what PMO said last
        // time to tell "the sync owns this field" from "a human deliberately changed it".
        String previousPmoManager = project.getExternalManagerName();

        project.setName(name);
        project.setExternalId(record.getExternalId());
        project.setExternalCustomerName(record.getCustomerName());
        project.setExternalManagerName(record.getManagerName());
        project.setExternalStatus(record.getStatus());
        project.setExternalPhase(record.getPhase());
        project.setExternalMigrationTypes(record.getMigrationTypes());
        project.setExternalSyncedAt(LocalDateTime.now());
        applyManager(project, record, previousPmoManager, managers, unresolved, result);

        if (isNew) {
            project.setCreatedBy(SYNC_CREATED_BY);
            project.setCreatedAt(LocalDateTime.now());
            // engineerEmails stays empty: PMO knows nothing about who works a project here, and an
            // engineer only sees a project once its Migration Manager assigns them to it.
            projectRepository.save(project);
            result.setCreatedCount(result.getCreatedCount() + 1);
            return;
        }

        if (Objects.equals(before, signature(project))) {
            // externalSyncedAt moved, nothing else did. Save it anyway so "when did the poll last see
            // this?" stays answerable, but do not report it as a change.
            projectRepository.save(project);
            result.setUnchangedCount(result.getUnchangedCount() + 1);
            return;
        }
        projectRepository.save(project);
        result.setUpdatedCount(result.getUpdatedCount() + 1);
    }

    /**
     * Guards the {@code UNIQUE} constraint on {@code Project.name} against a collision the batch itself
     * cannot see: a project already in this database under the same name -- typically one somebody
     * created here by hand before the sync existed. Rather than fail, or hijack the existing row (which
     * would attach PMO's identity to a project holding unrelated servers and sign-offs), the incoming
     * one takes a short id suffix.
     */
    private String resolveAgainstExistingNames(String desiredName, PmoProjectDto record, Project project) {
        Optional<Project> holder = projectRepository.findByNameIgnoreCase(desiredName);
        if (holder.isEmpty() || Objects.equals(holder.get().getId(), project.getId())) {
            return desiredName;
        }
        String suffixed = truncate(desiredName + " (" + shortId(record.getExternalId()) + ")");
        Optional<Project> suffixedHolder = projectRepository.findByNameIgnoreCase(suffixed);
        if (suffixedHolder.isEmpty() || Objects.equals(suffixedHolder.get().getId(), project.getId())) {
            log.info("PMO sync: \"{}\" is already taken by project {}, importing PMO {} as \"{}\".",
                    desiredName, holder.get().getId(), record.getExternalId(), suffixed);
            return suffixed;
        }
        throw new IllegalStateException("the name \"" + desiredName + "\" is already used by another project");
    }

    /**
     * Sets the Migration Manager from PMO's project manager, which is the same person by definition --
     * but only ever as a resolved email, and only when doing so cannot quietly overrule a human.
     *
     * <p>Three cases, in order:
     * <ol>
     *   <li><b>Unassigned here</b> -- take PMO's. This is the normal path for a new project.</li>
     *   <li><b>Assigned, and PMO agrees</b> -- nothing to do.</li>
     *   <li><b>Assigned to somebody else</b> -- overwrite ONLY if our value is still exactly what PMO's
     *       previous project manager resolved to, meaning the sync put it there and nobody has touched
     *       it since. Otherwise a person chose it deliberately, and silently reassigning would move an
     *       in-flight sign-off chain to a different first approver, so it is reported and left alone.</li>
     * </ol>
     *
     * <p>An unresolvable name never clears an existing assignment -- PMO not knowing our email
     * directory is not evidence that the manager here is wrong.
     */
    private void applyManager(Project project, PmoProjectDto record, String previousPmoManager,
                               ManagerIndex managers, Set<String> unresolved, PmoSyncResultDto result) {
        String pmoName = record.getManagerName();
        String resolved = resolveManagerEmail(pmoName, managers);
        if (resolved == null) {
            if (StringUtils.hasText(pmoName)) {
                unresolved.add(pmoName.trim());
            }
            return;
        }

        String current = project.getMigrationManagerName();
        if (!StringUtils.hasText(current)) {
            project.setMigrationManagerName(resolved);
            result.setManagersAssigned(result.getManagersAssigned() + 1);
            return;
        }
        if (current.equalsIgnoreCase(resolved)) {
            return;
        }

        String previouslyResolved = resolveManagerEmail(previousPmoManager, managers);
        if (previouslyResolved != null && current.equalsIgnoreCase(previouslyResolved)) {
            project.setMigrationManagerName(resolved);
            result.setManagersAssigned(result.getManagersAssigned() + 1);
            return;
        }
        result.addError("\"" + project.getName() + "\": PMO's project manager is " + pmoName + " ("
                + resolved + ") but this project is assigned to " + current
                + " here -- left unchanged. Reassign by hand if PMO is right.");
    }

    /**
     * Maps PMO's project-manager display name onto a real MIGRATION_MANAGER email, or null if it can't
     * be done unambiguously. PMO reports bare names ({@code Harika}, {@code Ajay Singh}) while identity
     * here is an email address, so this bridges the two by comparing against the email's local part.
     *
     * <p>Only two rules, both requiring a unique hit, because a false positive here is worse than no
     * match at all -- it would put the wrong person at the head of a sign-off chain:
     * <ol>
     *   <li>the whole name equals a manager's local part with separators as spaces
     *       ({@code Ajay Singh} -> {@code ajay.singh@...}, {@code Pranavi} -> {@code pranavi@...}),</li>
     *   <li>a single-word name equals exactly one manager's first name
     *       ({@code Harika} -> {@code harika.velidi@...}, {@code Sravan} -> {@code sravan.kesaram@...}).</li>
     * </ol>
     *
     * <p>Deliberately NOT first-name matching for multi-word names: a hypothetical PMO manager
     * "Ajay Kumar" would otherwise resolve to ajay.singh@ and hand that project to the wrong approver.
     *
     * <p>Against the live data on 2026-08-26 this resolved 8 of PMO's 11 project managers. The other
     * three are genuinely unresolvable rather than a matching failure: {@code Sriram Ramakrishnan} and
     * {@code Chandra Mouli} hold MIGRATION_ENGINEER accounts here, not manager ones -- and assigning an
     * engineer would make the project unapprovable, since the chain's first step requires the manager
     * role -- while {@code Nivas} has no account at all.
     */
    private static String resolveManagerEmail(String pmoManagerName, ManagerIndex index) {
        if (!StringUtils.hasText(pmoManagerName)) {
            return null;
        }
        String key = nameKey(pmoManagerName);
        String exact = index.byFullName().get(key);
        if (exact != null) {
            return exact;
        }
        if (!key.contains(" ")) {
            List<String> candidates = index.byFirstName().get(key);
            if (candidates != null && candidates.size() == 1) {
                return candidates.get(0);
            }
        }
        return null;
    }

    /** Indexes over MIGRATION_MANAGER emails, keyed the way {@link #resolveManagerEmail} looks them up. */
    private record ManagerIndex(Map<String, String> byFullName, Map<String, List<String>> byFirstName) {
    }

    private ManagerIndex buildManagerIndex() {
        Map<String, String> byFullName = new HashMap<>();
        Map<String, List<String>> byFirstName = new HashMap<>();
        for (String email : appUserService.emailsForRole(AppUserRole.MIGRATION_MANAGER)) {
            if (!StringUtils.hasText(email)) {
                continue;
            }
            int at = email.indexOf('@');
            String localPart = at > 0 ? email.substring(0, at) : email;
            String key = nameKey(localPart);
            if (key.isEmpty()) {
                continue;
            }
            // putIfAbsent, not put: if two managers somehow normalise to the same key, keep the first
            // and let the size>1 check in byFirstName be what refuses an ambiguous match.
            byFullName.putIfAbsent(key, email);
            byFirstName.computeIfAbsent(key.split(" ")[0], k -> new ArrayList<>()).add(email);
        }
        return new ManagerIndex(byFullName, byFirstName);
    }

    /**
     * Lowercases and reduces anything that isn't a letter or digit to a single space, so an email local
     * part and a typed display name land in the same shape: {@code lakshmi.prasanna} and
     * {@code "Lakshmi prasanna"} both become {@code "lakshmi prasanna"}.
     */
    private static String nameKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The fields this sync owns, so an unchanged poll can be told apart from a real update. */
    private static String signature(Project project) {
        return String.join(" ",
                String.valueOf(project.getName()),
                String.valueOf(project.getMigrationManagerName()),
                String.valueOf(project.getExternalCustomerName()),
                String.valueOf(project.getExternalManagerName()),
                String.valueOf(project.getExternalStatus()),
                String.valueOf(project.getExternalPhase()),
                String.valueOf(project.getExternalMigrationTypes()));
    }

    private static Set<String> parseStatuses(String raw) {
        Set<String> statuses = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            // Blank means "no status filter" -- import whatever PMO returns.
            return statuses;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .forEach(statuses::add);
        return statuses;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String shortId(String externalId) {
        if (externalId == null) {
            return "unknown";
        }
        return externalId.length() <= 8 ? externalId : externalId.substring(0, 8);
    }

    private static String truncate(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }
}
