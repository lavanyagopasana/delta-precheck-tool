package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds the first admin so the access allowlist isn't a chicken-and-egg problem -- someone has to
 * already be an admin before anyone can use the "add a user" API at all.
 *
 * <p>Who that is comes from app.first-admin-email/APP_FIRST_ADMIN_EMAIL, not a compiled-in constant:
 * a specific person's address baked into this class seeded the wrong admin on every database created
 * anywhere else, and the only way to correct it was a direct SQL edit. Blank disables seeding
 * entirely (for an environment that provisions its admin row some other way).
 */
// Must run before TeamRosterBootstrap (@Order(20)): TeamRosterBootstrap's own comment assumes
// this ordering ("that one only seeds into a completely empty app_users table, and creating
// roster rows first would stop it ever running"), but a CommandLineRunner with no @Order defaults
// to Ordered.LOWEST_PRECEDENCE -- meaning it actually ran LAST, after TeamRosterBootstrap had
// already inserted the roster and left app_users non-empty. On a fresh database that meant no
// admin was ever seeded at all, and everyone -- including whoever should have been admin --
// silently auto-provisioned as MIGRATION_ENGINEER instead, with no in-app way to fix it (Manage
// Access itself requires ADMIN). Confirmed and manually repaired once already; this fixes the
// root cause so it can't recur on a fresh database.
@Component
@Order(10)
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository appUserRepository;

    @Value("${app.first-admin-email:}")
    private String firstAdminEmail;

    public AdminBootstrap(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public void run(String... args) {
        if (appUserRepository.count() > 0) {
            return;
        }
        if (!StringUtils.hasText(firstAdminEmail)) {
            // Loud, because an empty app_users table with allowlisting on means nobody can sign in
            // and there is no in-app way out of it.
            log.warn("app_users is empty and app.first-admin-email is blank -- no admin was seeded, "
                    + "so nobody can sign in. Set APP_FIRST_ADMIN_EMAIL and restart.");
            return;
        }
        appUserRepository.save(new AppUser(firstAdminEmail.trim(), AppUserRole.ADMIN, "system"));
        log.info("Seeded first admin {} into an empty app_users table.", firstAdminEmail.trim());
    }
}
