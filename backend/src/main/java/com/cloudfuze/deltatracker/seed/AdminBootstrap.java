package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
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
@Component
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
