package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the first admin so the access allowlist isn't a chicken-and-egg problem -- someone has to
 * already be an admin before anyone can use the "add a user" API at all.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final String FIRST_ADMIN_EMAIL = "lavanya.gopasana@cloudfuze.com";

    private final AppUserRepository appUserRepository;

    public AdminBootstrap(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public void run(String... args) {
        if (appUserRepository.count() > 0) {
            return;
        }
        appUserRepository.save(new AppUser(FIRST_ADMIN_EMAIL, AppUserRole.ADMIN, "system"));
    }
}
