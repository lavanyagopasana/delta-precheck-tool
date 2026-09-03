package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.RosterDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the roster cache against two methods sharing one key.
 *
 * <p>Deliberately a @SpringBootTest and not a Mockito test: @Cacheable only does anything behind
 * the Spring proxy, so the unit tests for these two services -- which call them directly -- cannot
 * see this class of bug at all, and did not.
 *
 * <p>What happened: both {@code AppUserService.managerCandidateEmails()} and
 * {@code TeamService.engineersByManager()} are no-arg and cached in ROSTER_EMAILS_CACHE. With the
 * key left to the default, both keyed on SimpleKey.EMPTY, so the second caller read the first's
 * value -- {@code ClassCastException: ListN cannot be cast to Map} straight out of GET /api/roster,
 * taking down the manager picker and the Teams page with it. Calling both in one context is the
 * whole test; it throws on the broken version.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Without this the context resolves the REAL datasource from application.properties and the
// first query fails on a schema that isn't there; every @SpringBootTest here loads it for H2.
@TestPropertySource(locations = "classpath:/application-test.properties")
class RosterCacheKeyTest {

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private TeamService teamService;

    @Test
    void theTwoNoArgCachedRosterLookupsDoNotShareACacheKey() {
        // Populate in both orders: whichever writes first, the other must still get its own type.
        Map<String, List<String>> engineersFirst = teamService.engineersByManager();
        List<String> candidates = appUserService.managerCandidateEmails();
        Map<String, List<String>> engineersAgain = teamService.engineersByManager();
        List<String> candidatesAgain = appUserService.managerCandidateEmails();

        assertThat(engineersAgain).isEqualTo(engineersFirst);
        assertThat(candidatesAgain).isEqualTo(candidates);
    }

    @Test
    void aPerRoleLookupIsNotServedAnotherMethodsCachedValue() {
        // emailsForRole takes an argument, so it was never part of the collision -- but it shares
        // the cache, and asserting it here keeps the whole cache's key space covered.
        appUserService.managerCandidateEmails();
        teamService.engineersByManager();

        assertThat(appUserService.emailsForRole(AppUserRole.MIGRATION_MANAGER)).isNotNull();
        assertThat(appUserService.emailsForRole(AppUserRole.DEV_LEAD)).isNotNull();
    }

    @Test
    void theRosterResponseItselfBuildsWithEveryCachedLookupWarm() {
        // The exact sequence RosterController performs, which is where this surfaced.
        RosterDto dto = new RosterDto();
        dto.setMigrationManagers(appUserService.managerCandidateEmails());
        dto.setEngineers(appUserService.emailsForRole(AppUserRole.MIGRATION_ENGINEER));
        dto.setDevLeads(appUserService.emailsForRole(AppUserRole.DEV_LEAD));
        dto.setQaLeads(appUserService.emailsForRole(AppUserRole.QA_LEAD));
        dto.setEngineersByManager(teamService.engineersByManager());

        assertThat(dto.getMigrationManagers()).isNotNull();
        assertThat(dto.getEngineersByManager()).isNotNull();
    }
}
