package com.cloudfuze.deltatracker.config;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Copies the pre-multi-team {@code app_users.team_id} membership into the {@code app_user_teams}
 * join table, once, on startup.
 *
 * <p>This is not optional housekeeping. {@code ddl-auto=update} only ever ADDS: it creates the join
 * table but never drops the old column and never moves a single row into it. So on any database
 * that already has a roster -- production, and every developer's local copy -- membership lives
 * exclusively in {@code team_id} at the moment this version first boots. Without this pass every
 * engineer picker would come up unscoped and every team card empty, with the data still sitting
 * right there in a column nothing reads any more.
 *
 * <p>Idempotent by construction: a user is skipped unless their join-table set is empty, so a second
 * boot does nothing, and it can never undo a membership an admin has since edited. It follows the
 * same startup-repair pattern as {@code EnumCheckConstraintSync} -- schema that {@code update} mode
 * cannot get right on its own, fixed in code where it is visible.
 *
 * <p><b>Order matters, and getting it wrong is destructive.</b> This must run before
 * {@code TeamRosterBootstrap} (@Order(20)), which decides whether a group's team already exists by
 * asking whether any of its managers is on one. Hung on ApplicationReadyEvent -- after every
 * CommandLineRunner -- it ran too late: the seed saw an empty join table, concluded nobody had a
 * team, and created a duplicate team for every roster group while the real ones were left looking
 * empty. Hence a CommandLineRunner at @Order(5), ahead of AdminBootstrap (10) and the roster seed,
 * so every later runner sees true membership.
 */
@Component
@Order(5)
public class TeamMembershipBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TeamMembershipBackfill.class);

    private final AppUserRepository appUserRepository;

    public TeamMembershipBackfill(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional
    @SuppressWarnings("deprecation") // AppUser.getTeam() exists for exactly this migration.
    public void run(String... args) {
        List<AppUser> migrated = new ArrayList<>();
        for (AppUser user : appUserRepository.findAll()) {
            Team legacy = user.getTeam();
            // Only ever fills a gap. A non-empty set means either this already ran or an admin has
            // since chosen the memberships, and neither should be touched.
            if (legacy == null || !user.getTeams().isEmpty()) {
                continue;
            }
            user.getTeams().add(legacy);
            migrated.add(user);
        }
        if (migrated.isEmpty()) {
            return;
        }
        appUserRepository.saveAll(migrated);
        log.info("Team membership backfill: moved {} single-team membership(s) into app_user_teams. "
                + "app_users.team_id is now unused -- the join table is the source of truth.",
                migrated.size());
    }
}
