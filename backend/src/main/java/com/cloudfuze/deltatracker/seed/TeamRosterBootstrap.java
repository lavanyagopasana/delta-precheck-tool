package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.repository.TeamRepository;
import com.cloudfuze.deltatracker.util.CsvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates the delivery teams and their members on startup, from
 * {@code src/main/resources/seed/teams-roster.csv}.
 *
 * <p>Exists because the alternative was an admin hand-importing a CSV on every fresh database --
 * including production, right after a deploy, before the engineer pickers scope to anything. That is
 * work a person should not have to remember, and forgetting it is invisible: the app looks fine and
 * simply lists every engineer everywhere.
 *
 * <p><b>Grouped by MANAGER, not by team name.</b> This is what makes it safe to run against a
 * database an admin has already been editing. If a group's manager already has a team, that team IS
 * this group's team -- it is REUSED and its missing members are added, rather than a duplicate being
 * created beside it. Matching on the manager rather than on the string "Team 4" is what makes that
 * work when the admin named their team something else, which the UI's auto-numbering makes likely.
 *
 * <p>Never modifies an ADMIN row, and never moves somebody who already has a team. Runs on every
 * boot and converges: once the roster is in place it does nothing but a handful of reads.
 *
 * <p>Disable with {@code app.seed-team-roster=false} (or {@code APP_SEED_TEAM_ROSTER=false}) for a
 * deployment that manages its own roster.
 */
@Component
// After AdminBootstrap: that one only seeds into a completely empty app_users table, and creating
// roster rows first would stop it ever running.
@Order(20)
public class TeamRosterBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamRosterBootstrap.class);
    private static final String RESOURCE = "seed/teams-roster.csv";

    private final TeamRepository teamRepository;
    private final AppUserRepository appUserRepository;

    @Value("${app.seed-team-roster:true}")
    private boolean enabled;

    public TeamRosterBootstrap(TeamRepository teamRepository, AppUserRepository appUserRepository) {
        this.teamRepository = teamRepository;
        this.appUserRepository = appUserRepository;
    }

    /** One row of the roster file. */
    private record Row(String email, AppUserRole role, String team) {}

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            log.info("Team roster seeding is disabled (app.seed-team-roster=false).");
            return;
        }

        List<Row> rows = readRoster();
        if (rows.isEmpty()) {
            return;
        }

        // Preserve file order so team numbers follow the order they are written in.
        Map<String, List<Row>> byTeam = new LinkedHashMap<>();
        for (Row r : rows) {
            byTeam.computeIfAbsent(r.team(), k -> new ArrayList<>()).add(r);
        }

        int teamsCreated = 0;
        int usersCreated = 0;
        int completedGroups = 0;

        for (Map.Entry<String, List<Row>> group : byTeam.entrySet()) {
            List<Row> members = group.getValue();
            List<Row> managers = members.stream().filter(r -> r.role() == AppUserRole.MIGRATION_MANAGER).toList();

            if (managers.isEmpty()) {
                log.warn("Roster group '{}' names no Migration Manager -- skipped, since a team with no "
                        + "manager cannot scope anybody's engineer list.", group.getKey());
                continue;
            }

            // Does a manager of this group already have a team? If so that IS this group's team --
            // an admin built it by hand -- so reuse it rather than creating a second one beside it.
            Team existingTeam = managers.stream()
                    .map(m -> appUserRepository.findByEmailIgnoreCase(m.email()).orElse(null))
                    .filter(u -> u != null && u.getTeam() != null)
                    .map(AppUser::getTeam)
                    .findFirst()
                    .orElse(null);

            Team team;
            if (existingTeam != null) {
                // Reused, not skipped. Skipping the group outright left a half-finished manual setup
                // half-finished forever: an admin who created the team and its manager but not its
                // engineers would never get the engineers, and the picker would scope to nobody.
                // Members who already have a team are still never moved, so their work is intact.
                team = existingTeam;
                completedGroups++;
            } else {
                // A team NAME already in use does not mean this group's team exists. An admin
                // creating teams through the UI gets them auto-numbered from whatever is already
                // there, so their hand-made "Team 1" can easily belong to a different manager.
                // Reusing it by name would merge two unrelated teams. Take the name if free,
                // otherwise allocate a fresh one.
                team = teamRepository.save(new Team(freeTeamName(group.getKey()), "roster-seed"));
                teamsCreated++;
            }

            for (Row r : members) {
                Optional<AppUser> existing = appUserRepository.findByEmailIgnoreCase(r.email());
                if (existing.isEmpty()) {
                    AppUser created = new AppUser(r.email(), r.role(), "roster-seed");
                    created.setTeam(team);
                    appUserRepository.save(created);
                    usersCreated++;
                    continue;
                }
                AppUser user = existing.get();
                // An admin is deliberately outside the team structure, and demoting one here would
                // be a privilege change nobody asked for.
                if (user.getRole() == AppUserRole.ADMIN) {
                    continue;
                }
                // Only fill in what is missing. Somebody already placed on a team stays there.
                if (user.getTeam() == null) {
                    user.setTeam(team);
                    user.setRole(r.role());
                    appUserRepository.save(user);
                }
            }
        }

        if (teamsCreated > 0 || usersCreated > 0) {
            log.info("Team roster seeded: {} team(s) created, {} user(s) added, {} existing team(s) "
                    + "reused (created by an admin, members topped up).",
                    teamsCreated, usersCreated, completedGroups);
        } else {
            log.debug("Team roster already in place ({} existing team(s) checked).", completedGroups);
        }
    }

    /**
     * The group's own name if nothing holds it, otherwise the next free "Team N".
     *
     * <p>Only the stored name is at stake here -- the UI labels every team by its managers and
     * numbers them by creation order -- so any unused name is as good as another. What matters is
     * never handing back a team that belongs to somebody else.
     */
    private String freeTeamName(String preferred) {
        if (teamRepository.findByNameIgnoreCase(preferred).isEmpty()) {
            return preferred;
        }
        for (int n = 1; n <= 200; n++) {
            String candidate = "Team " + n;
            if (teamRepository.findByNameIgnoreCase(candidate).isEmpty()) {
                return candidate;
            }
        }
        // 200 teams named "Team N" already exist. Fall back to something guaranteed unique rather
        // than looping forever or failing startup.
        return preferred + " (seeded)";
    }

    private List<Row> readRoster() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("{} is not on the classpath -- no teams were seeded.", RESOURCE);
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                List<String> fields = CsvUtils.parseLine(line);
                if (first) {
                    first = false;
                    // Skip the header. Detected rather than assumed so a file without one still loads.
                    if (fields.size() > 0 && fields.get(0).trim().equalsIgnoreCase("email")) {
                        continue;
                    }
                }
                if (fields.size() < 3) {
                    log.warn("{} line {}: expected email,role,team -- skipped.", RESOURCE, lineNo);
                    continue;
                }
                String email = fields.get(0).trim().toLowerCase();
                AppUserRole role = parseRole(fields.get(1));
                String team = fields.get(2).trim();
                if (!email.contains("@") || role == null || !StringUtils.hasText(team)) {
                    log.warn("{} line {}: unusable row for '{}' -- skipped.", RESOURCE, lineNo, email);
                    continue;
                }
                rows.add(new Row(email, role, team));
            }
        } catch (Exception e) {
            // A malformed seed file must never stop the application from starting.
            log.warn("Could not read {} -- no teams were seeded.", RESOURCE, e);
            return List.of();
        }
        return rows;
    }

    // Accepts the enum name and the label the UI shows ("Migration Manager"), matching how
    // AppUserService.importCsv resolves a role cell.
    private AppUserRole parseRole(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase().replaceAll("[^a-z]", "");
        if (normalized.isEmpty()) {
            return null;
        }
        for (AppUserRole candidate : AppUserRole.values()) {
            if (candidate.name().toLowerCase().replaceAll("[^a-z]", "").equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
