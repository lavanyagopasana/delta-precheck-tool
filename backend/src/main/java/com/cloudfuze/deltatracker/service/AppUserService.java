package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.config.CacheConfig;
import com.cloudfuze.deltatracker.dto.AppUserDto;
import com.cloudfuze.deltatracker.dto.AppUserImportResultDto;
import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.repository.TeamRepository;
import com.cloudfuze.deltatracker.util.CsvUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class AppUserService {

    private static final String ACCEPTED_ROLES_MESSAGE =
            "Accepted roles: Admin, Migration Manager, Dev Lead, QA Lead, Migration Engineer.";

    private final AppUserRepository appUserRepository;

    // Temporary testing switch: while false, everyone with a valid token is treated as allowed,
    // regardless of the allowlist table. Set AZURE_REQUIRE_ALLOWLIST=true to go back to requiring
    // an admin to explicitly add each person. requireAdmin() below is NOT affected by this -- the
    // Manage Access page itself always requires a real ADMIN row, even while this is off.
    @Value("${azure.require-allowlist:false}")
    private boolean requireAllowlist;

    // Anyone signing in with this email domain is auto-added to the allowlist (if not already
    // present) as MIGRATION_ENGINEER the first time they're looked up -- no admin action needed.
    // This runs regardless of azure.require-allowlist. Existing rows (e.g. someone later promoted
    // to Admin) are never overwritten by this -- it only creates a row when one doesn't exist yet.
    @Value("${azure.auto-provision-domain:cloudfuze.com}")
    private String autoProvisionDomain;

    private final RosterCache rosterCache;
    private final TeamRepository teamRepository;

    public AppUserService(AppUserRepository appUserRepository, RosterCache rosterCache,
                           TeamRepository teamRepository) {
        this.appUserRepository = appUserRepository;
        this.rosterCache = rosterCache;
        this.teamRepository = teamRepository;
    }

    public List<AppUserDto> list() {
        return appUserRepository.findAllByOrderByAddedAtAsc().stream()
                .map(AppUserDto::fromEntity)
                .toList();
    }

    // Master/lookup data: read constantly (roster dropdowns, notification recipient lists), written
    // rarely. Cached per-role; every write path below evicts the whole cache so a role change or a
    // newly-added user is reflected immediately, never served stale.
    @Cacheable(CacheConfig.ROSTER_EMAILS_CACHE)
    public List<String> emailsForRole(AppUserRole role) {
        return appUserRepository.findByRole(role).stream().map(AppUser::getEmail).toList();
    }

    /**
     * Everyone who may be named a project's Migration Manager: the MIGRATION_MANAGER role, plus
     * anyone an admin has flagged as assignable whatever their own role.
     *
     * <p>Exists because the picker used to be exactly emailsForRole(MIGRATION_MANAGER), so an admin
     * who also runs engagements simply was not offered -- and nothing on the write side rejected
     * them, the name just never appeared to be chosen. Shares the roster cache with the per-role
     * lists and is evicted by the same writes.
     *
     * <p>The key is spelled out. Left to the default, a no-arg method keys on SimpleKey.EMPTY --
     * and TeamService.engineersByManager is also no-arg on this same cache, so the two overwrote
     * each other and whichever read second got the other's value: a live
     * {@code ClassCastException: ListN cannot be cast to Map} out of GET /api/roster, which is what
     * feeds the manager picker and the Teams page. Any further no-arg method on this cache needs
     * its own key too.
     */
    @Cacheable(value = CacheConfig.ROSTER_EMAILS_CACHE, key = "'managerCandidates'")
    public List<String> managerCandidateEmails() {
        // Deduplicated case-insensitively: a flagged MIGRATION_MANAGER is in both source lists, and
        // email is the identity key everywhere else in this app.
        Map<String, String> byLowercase = new LinkedHashMap<>();
        for (AppUser user : appUserRepository.findByRole(AppUserRole.MIGRATION_MANAGER)) {
            byLowercase.putIfAbsent(user.getEmail().toLowerCase(), user.getEmail());
        }
        for (AppUser user : appUserRepository.findByAssignableAsManagerTrue()) {
            byLowercase.putIfAbsent(user.getEmail().toLowerCase(), user.getEmail());
        }
        return byLowercase.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // Drop the whole roster cache whenever the app_users table changes. Called from every write
    // path (manual rather than @CacheEvict so it also fires for the internal importCsv -> upsert
    // self-call, which a proxy-based annotation would silently miss). The clearing itself lives in
    // RosterCache because TeamService must evict the same cache for team edits.
    private void evictRosterCache() {
        rosterCache.evict();
    }

    public Optional<AppUserRole> roleOf(String email) {
        if (email == null) {
            return Optional.empty();
        }
        autoProvisionIfEligible(email);
        return appUserRepository.findByEmailIgnoreCase(email).map(AppUser::getRole);
    }

    public boolean isAllowed(String email) {
        autoProvisionIfEligible(email);
        if (!requireAllowlist) {
            return true;
        }
        return email != null && appUserRepository.existsByEmailIgnoreCase(email);
    }

    private void autoProvisionIfEligible(String email) {
        if (email == null || !StringUtils.hasText(autoProvisionDomain)) {
            return;
        }
        if (!email.toLowerCase().endsWith("@" + autoProvisionDomain.toLowerCase())) {
            return;
        }
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            return;
        }
        appUserRepository.save(new AppUser(email, AppUserRole.MIGRATION_ENGINEER, "auto (" + autoProvisionDomain + ")"));
        evictRosterCache();
    }

    public void requireAdmin(String email) {
        if (roleOf(email).filter(r -> r == AppUserRole.ADMIN).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only admins can manage app access.");
        }
    }

    public boolean isAdmin(String email) {
        return roleOf(email).filter(r -> r == AppUserRole.ADMIN).isPresent();
    }

    /**
     * ADMIN or MIGRATION_MANAGER -- the roles that may edit and delete project data.
     *
     * <p>Managers were given the destructive project actions (delete a server, decommission, clear a
     * combination's pairs, remove a Metabase database) alongside admins, because they own delivery
     * for their projects and each of those was previously an admin-only errand.
     *
     * <p>Deliberately NOT used for three things, which stay ADMIN-only: filling in a pre-check (the
     * manager is the chain's first approver, so it would collapse two steps into one person), team
     * writes (team membership decides which engineers a manager may assign, so they could widen
     * their own pool), and the user allowlist. One predicate rather than a role check at each call
     * site, so the boundary is stated once.
     */
    public boolean canEditProjectData(String email) {
        return roleOf(email)
                .filter(r -> r == AppUserRole.ADMIN || r == AppUserRole.MIGRATION_MANAGER)
                .isPresent();
    }

    // actingAdminEmail is the admin performing the change (from AdminController.requireAdmin). It's
    // stored as addedBy for brand-new rows, and used to guard the scenarios where editing a user
    // isn't safe -- so "editing users" is deliberately NOT allowed for every user/every change.
    public AppUser upsertEntity(String email, AppUserRole role, String actingAdminEmail) {
        return upsertEntity(email, role, null, actingAdminEmail);
    }

    /**
     * @param assignableAsManager null leaves an existing row's flag untouched -- CSV import and the
     *     admin bootstrap both come through here and must not silently clear a flag they know
     *     nothing about. A new row created with null defaults to false, as the entity does.
     */
    public AppUser upsertEntity(String email, AppUserRole role, Boolean assignableAsManager,
                                 String actingAdminEmail) {
        Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(email);

        if (existing.isPresent()) {
            AppUser current = existing.get();
            boolean roleChanging = current.getRole() != role;
            // You can't change your own role -- another admin must, so an admin can't accidentally
            // demote themselves out of Manage Access.
            if (roleChanging && email.equalsIgnoreCase(actingAdminEmail)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You can't change your own role.");
            }
            // There must always be at least one admin -- the last one can't be demoted.
            if (roleChanging && current.getRole() == AppUserRole.ADMIN && role != AppUserRole.ADMIN
                    && appUserRepository.countByRole(AppUserRole.ADMIN) <= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Can't demote the last remaining admin.");
            }
        }

        AppUser user = existing.orElseGet(() -> new AppUser(email, role, actingAdminEmail));
        user.setRole(role);
        if (assignableAsManager != null) {
            user.setAssignableAsManager(assignableAsManager);
        }
        AppUser saved = appUserRepository.save(user);
        evictRosterCache();
        return saved;
    }

    // DTO-returning wrapper for the controller. upsertEntity above returns the saved row itself,
    // which importCsv needs so it can set the team without re-reading what it just wrote.
    public AppUserDto upsert(String email, AppUserRole role, String actingAdminEmail) {
        return upsert(email, role, null, actingAdminEmail);
    }

    public AppUserDto upsert(String email, AppUserRole role, Boolean assignableAsManager,
                              String actingAdminEmail) {
        return AppUserDto.fromEntity(upsertEntity(email, role, assignableAsManager, actingAdminEmail));
    }

    // One person per row. A header row is auto-detected if any cell normalizes to "email", "role" or
    // "team"; otherwise every row (including the first) is data with the email in the first column.
    //
    // Team resolution per row: an optional "team" column names a team, matched case-insensitively.
    // A name that doesn't exist yet is CREATED, and every team created this way is listed in the
    // result's createdTeams.
    //
    // This deliberately does not error on an unknown team. Requiring the teams to exist first meant
    // onboarding a whole org was two manual steps -- hand-create six teams, then import -- and got
    // the ordering wrong often enough to be the main friction in setting this up at all. The typo
    // risk that argued for erroring is covered by reporting instead: a mistyped cell surfaces as a
    // team nobody meant to create, both in createdTeams and in the Teams panel, rather than silently
    // leaving that person team-less.
    //
    // Role resolution per row: the row's own "role" cell wins, falling back to defaultRole when that
    // cell is blank or the column is absent. defaultRole may be null, which is valid as long as every
    // row carries its own role -- a row with neither is reported as a row error rather than failing
    // the file, matching how a bad email is handled.
    //
    // Every valid row is upserted, same as adding one at a time: an email already on the allowlist has
    // its role updated, never duplicated.
    public AppUserImportResultDto importCsv(MultipartFile file, AppUserRole defaultRole, String addedBy) {
        List<String> lines = readLines(file);
        if (lines.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV file is empty");
        }

        // A "role" column lets one file carry people of mixed roles, which is the normal case when
        // onboarding a whole team -- previously every role needed its own separate import because the
        // single defaultRole applied to every row. The column is optional and per-row: a blank cell
        // falls back to defaultRole, so existing single-role files keep working unchanged.
        int emailColumn = 0;
        int roleColumn = -1;
        int teamColumn = -1;
        int startRow = 0;
        List<String> header = CsvUtils.parseLine(lines.get(0));
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalizeHeader(header.get(i));
            if (normalized.equals("email")) {
                emailColumn = i;
                startRow = 1;
            } else if (normalized.equals("role")) {
                roleColumn = i;
                startRow = 1;
            } else if (normalized.equals("team")) {
                teamColumn = i;
                startRow = 1;
            }
        }

        List<String> errors = new ArrayList<>();
        // Ordered + de-duplicated: a team named by 6 rows must be created once and reported once.
        LinkedHashSet<String> createdTeams = new LinkedHashSet<>();
        int totalRows = 0;
        int created = 0;
        int updated = 0;

        for (int rowNum = startRow; rowNum < lines.size(); rowNum++) {
            String line = lines.get(rowNum);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            totalRows++;
            List<String> fields = CsvUtils.parseLine(line);
            String email = emailColumn < fields.size() ? fields.get(emailColumn).trim().toLowerCase() : "";
            if (!StringUtils.hasText(email) || !email.contains("@")) {
                errors.add("Row " + (rowNum + 1) + ": \"" + email + "\" isn't a valid email");
                continue;
            }

            AppUserRole rowRole = defaultRole;
            String rawRole = roleColumn >= 0 && roleColumn < fields.size() ? fields.get(roleColumn).trim() : "";
            if (StringUtils.hasText(rawRole)) {
                rowRole = parseRole(rawRole);
                if (rowRole == null) {
                    errors.add("Row " + (rowNum + 1) + ": \"" + rawRole + "\" isn't a known role. "
                            + ACCEPTED_ROLES_MESSAGE);
                    continue;
                }
            } else if (rowRole == null) {
                // No role in the row and no fallback chosen. Naming the row rather than failing the
                // whole file keeps this consistent with every other per-row error here.
                errors.add("Row " + (rowNum + 1) + ": no role given for \"" + email
                        + "\" and no default role was selected. Add a role column value or pick a default.");
                continue;
            }

            // Resolved BEFORE the upsert so an unknown team name doesn't half-apply the row --
            // otherwise the person would be created/updated and then reported as an error.
            //
            // Several teams may be named in one cell, separated by ";" -- a comma would be eaten by
            // the CSV split itself. One name behaves exactly as it always did.
            List<Team> rowTeams = new ArrayList<>();
            String rawTeam = teamColumn >= 0 && teamColumn < fields.size() ? fields.get(teamColumn).trim() : "";
            if (StringUtils.hasText(rawTeam)) {
                for (String namePart : rawTeam.split(";")) {
                    String teamName = namePart.trim();
                    if (teamName.isEmpty()) {
                        continue;
                    }
                    Optional<Team> found = teamRepository.findByNameIgnoreCase(teamName);
                    Team resolved;
                    if (found.isPresent()) {
                        resolved = found.get();
                    } else {
                        resolved = teamRepository.save(new Team(teamName, addedBy));
                        createdTeams.add(resolved.getName());
                    }
                    if (rowTeams.stream().noneMatch(t -> t.getId().equals(resolved.getId()))) {
                        rowTeams.add(resolved);
                    }
                }
            }

            boolean existed = appUserRepository.existsByEmailIgnoreCase(email);
            try {
                AppUser stored = upsertEntity(email, rowRole, addedBy);
                if (!rowTeams.isEmpty()) {
                    // Set on the row upsertEntity just returned rather than re-reading it. Applied as
                    // a second save because upsert owns the role guards (self-demotion, last admin)
                    // and stays team-agnostic, so team assignment can't be blocked by them.
                    //
                    // The named teams REPLACE that person's memberships, so re-importing a corrected
                    // file converges instead of piling teams up every run. A blank cell still leaves
                    // membership untouched, which is what makes a role-only file safe to import.
                    stored.getTeams().clear();
                    stored.getTeams().addAll(rowTeams);
                    appUserRepository.save(stored);
                    evictRosterCache();
                }
            } catch (ApiException e) {
                // Keep processing the rest of the batch -- one guarded row (e.g. the acting admin's
                // own, or a last-admin demotion) must not fail the whole import.
                errors.add("Row " + (rowNum + 1) + ": " + e.getMessage());
                continue;
            }
            if (existed) {
                updated++;
            } else {
                created++;
            }
        }

        AppUserImportResultDto result = new AppUserImportResultDto();
        result.setTotalRows(totalRows);
        result.setCreatedCount(created);
        result.setUpdatedCount(updated);
        result.setErrors(errors);
        result.setCreatedTeams(new ArrayList<>(createdTeams));
        // A new team changes which engineers a manager may assign, so the roster map is now stale.
        if (!createdTeams.isEmpty()) {
            evictRosterCache();
        }
        return result;
    }

    /**
     * Resolves a CSV role cell to an {@link AppUserRole}, or null when it matches nothing.
     *
     * <p>Matches on the same normalization used for headers (lowercase, letters only) so that the
     * enum name and the label shown in the UI both work: "MIGRATION_ENGINEER", "Migration Engineer"
     * and "migration engineer" all resolve. People fill these files in by copying what the screen
     * shows them, not the enum constant, so accepting only the constant would reject the most likely
     * input.
     */
    private AppUserRole parseRole(String raw) {
        String normalized = normalizeHeader(raw);
        if (normalized.isEmpty()) {
            return null;
        }
        for (AppUserRole candidate : AppUserRole.values()) {
            if (normalizeHeader(candidate.name()).equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        String cleaned = header.replace("﻿", "");
        return cleaned.trim().toLowerCase().replaceAll("[^a-z]", "");
    }

    private List<String> readLines(MultipartFile file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read CSV file");
        }
        return lines;
    }

    public void remove(String email, String actingAdminEmail) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        // You can't remove your own access -- prevents locking yourself out; another admin must.
        if (email.equalsIgnoreCase(actingAdminEmail)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't remove your own access.");
        }
        if (user.getRole() == AppUserRole.ADMIN && appUserRepository.countByRole(AppUserRole.ADMIN) <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot remove the last remaining admin.");
        }

        appUserRepository.delete(user);
        evictRosterCache();
    }
}
