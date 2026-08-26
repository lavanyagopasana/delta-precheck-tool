package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Team;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import com.cloudfuze.deltatracker.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the roster actually lands, and -- more importantly -- that re-running it against a database
 * somebody has been editing by hand neither duplicates their work nor overrides it.
 *
 * <p>The bootstrap has already run once by the time these tests execute: it is a CommandLineRunner,
 * so bringing up the context IS the first invocation. That makes the first assertion a genuine
 * end-to-end check of startup seeding rather than a simulation of it.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        locations = "classpath:/application-test.properties",
        // Re-enabled just for this class. The test profile disables it so the seeded roster cannot
        // leak into every other @SpringBootTest through the shared H2 database.
        properties = "app.seed-team-roster=true")
class TeamRosterBootstrapTest {

    @Autowired
    private TeamRosterBootstrap bootstrap;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private List<String> emailsOf(String teamName, AppUserRole role) {
        Team team = teamRepository.findByNameIgnoreCase(teamName).orElseThrow();
        return appUserRepository.findByTeamIdAndRole(team.getId(), role).stream()
                .map(AppUser::getEmail)
                .toList();
    }

    @Test
    void startupSeedsEverySixTeamsWithTheirManagersAndEngineers() {
        assertThat(teamRepository.findAllByOrderByNameAsc())
                .extracting(Team::getName)
                .contains("Team 1", "Team 2", "Team 3", "Team 4", "Team 5", "Team 6");

        assertThat(emailsOf("Team 1", AppUserRole.MIGRATION_MANAGER))
                .containsExactly("harika.velidi@cloudfuze.com");
        assertThat(emailsOf("Team 1", AppUserRole.MIGRATION_ENGINEER)).hasSize(3);

        // The two-manager teams are the reason Team is an entity rather than a column on AppUser.
        assertThat(emailsOf("Team 5", AppUserRole.MIGRATION_MANAGER))
                .containsExactlyInAnyOrder("abhishikth.yenugula@cloudfuze.com", "ajay.singh@cloudfuze.com");
        assertThat(emailsOf("Team 6", AppUserRole.MIGRATION_MANAGER))
                .containsExactlyInAnyOrder("abhishek.sakala@cloudfuze.com", "pranavi@cloudfuze.com");

        assertThat(emailsOf("Team 5", AppUserRole.MIGRATION_ENGINEER)).hasSize(5);
        assertThat(emailsOf("Team 6", AppUserRole.MIGRATION_ENGINEER)).hasSize(4);
    }

    @Test
    void everyLeadAndAdminStaysOutsideTheTeamStructure() {
        // Teams exist only to scope a Migration Manager's engineer list, so a DEV_LEAD/QA_LEAD/ADMIN
        // on one would be meaningless -- and for an ADMIN, a role change nobody asked for.
        assertThat(appUserRepository.findAll())
                .filteredOn(u -> u.getRole() == AppUserRole.ADMIN
                        || u.getRole() == AppUserRole.DEV_LEAD
                        || u.getRole() == AppUserRole.QA_LEAD)
                .allSatisfy(u -> assertThat(u.getTeam()).isNull());
    }

    @Test
    void runningAgainChangesNothing() {
        long teamsBefore = teamRepository.count();
        long usersBefore = appUserRepository.count();

        bootstrap.run();
        bootstrap.run();

        assertThat(teamRepository.count()).isEqualTo(teamsBefore);
        assertThat(appUserRepository.count()).isEqualTo(usersBefore);
    }

    // Mutates the seeded roster that the read-only assertions above depend on. The context (and with
    // it the create-drop H2 database) is rebuilt afterwards so this cannot decide whether another
    // test passes -- these all share one Spring context and JUnit does not guarantee method order.
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void aManagerAnAdminAlreadyPlacedByHandIsLeftWhereTheyAre() {
        // The case that makes running this on a live database safe: an admin has already put a
        // manager on a team of their own. That arrangement must win -- the seed must not move them
        // back to the team the file names, nor create a duplicate alongside it.
        Team handMade = teamRepository.save(new Team("Ad hoc team", "an-admin"));
        AppUser manager = appUserRepository.findByEmailIgnoreCase("sravan.kesaram@cloudfuze.com").orElseThrow();
        manager.setTeam(handMade);
        appUserRepository.save(manager);
        long teamsBefore = teamRepository.count();

        bootstrap.run();

        AppUser after = appUserRepository.findByEmailIgnoreCase("sravan.kesaram@cloudfuze.com").orElseThrow();
        // Compared by id, not by name: team is a LAZY association and this assertion runs outside a
        // session, so reading a mapped field would throw LazyInitializationException. getId() is
        // answered from the proxy itself without a fetch.
        assertThat(after.getTeam().getId()).isEqualTo(handMade.getId());
        assertThat(teamRepository.count()).isEqualTo(teamsBefore);
    }

    @Test
    void theThreeCorrectedAddressesAreSeededAtCloudfuzeCom() {
        // These differed from the list they were transcribed from and could never have worked as
        // given: email is the only identity key here, so a typo is an account nobody can sign in as.
        for (String email : List.of(
                "lakshmi.prasanna@cloudfuze.com",
                "ganesh.kondameedi@cloudfuze.com",
                "davidraj.dumpala@cloudfuze.com")) {
            Optional<AppUser> user = appUserRepository.findByEmailIgnoreCase(email);
            assertThat(user).as("%s should be seeded", email).isPresent();
            assertThat(user.orElseThrow().getTeam()).as("%s should be on a team", email).isNotNull();
        }
    }

    // Mutates the seeded roster that the read-only assertions above depend on. The context (and with
    // it the create-drop H2 database) is rebuilt afterwards so this cannot decide whether another
    // test passes -- these all share one Spring context and JUnit does not guarantee method order.
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void aHandMadeTeamOccupyingTheNameTheSeedWantsIsNeverReused() {
        // The real situation this guards. An admin creates teams through the UI, which auto-numbers
        // them from whatever exists -- so on a fresh database their first three are "Team 1".."Team 3"
        // and may belong to entirely different managers than the file's Team 1..Team 3 groups.
        //
        // Reusing a team by NAME would put this group's manager into somebody else's team. Only the
        // manager check decides whether a group is already set up; the name is just a label.
        Team squatter = teamRepository.save(new Team("Team 9", "an-admin"));
        AppUser outsider = appUserRepository.save(
                new AppUser("squatter.manager@cloudfuze.com", AppUserRole.MIGRATION_MANAGER, "an-admin"));
        outsider.setTeam(squatter);
        appUserRepository.save(outsider);

        // A roster manager with no team, whose group name is free but whose team must not become
        // the squatter's. Detach one so the group is reprocessed.
        AppUser rosterManager = appUserRepository.findByEmailIgnoreCase("raghu.yellani@cloudfuze.com").orElseThrow();
        Long originalTeamId = rosterManager.getTeam().getId();
        rosterManager.setTeam(null);
        appUserRepository.save(rosterManager);

        bootstrap.run();

        AppUser after = appUserRepository.findByEmailIgnoreCase("raghu.yellani@cloudfuze.com").orElseThrow();
        assertThat(after.getTeam()).as("the roster manager should be placed on a team").isNotNull();
        assertThat(after.getTeam().getId())
                .as("must never land in a team an admin made for somebody else")
                .isNotEqualTo(squatter.getId());
        assertThat(appUserRepository.findByTeamId(squatter.getId()))
                .extracting(AppUser::getEmail)
                .containsExactly("squatter.manager@cloudfuze.com");
        assertThat(originalTeamId).isNotNull();
    }

    // Mutates the seeded roster that the read-only assertions above depend on. The context (and with
    // it the create-drop H2 database) is rebuilt afterwards so this cannot decide whether another
    // test passes -- these all share one Spring context and JUnit does not guarantee method order.
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void aHalfFinishedManualTeamGetsItsMissingEngineers() {
        // The situation after an admin creates a few teams by hand in the deployed app: the team and
        // its manager exist, some or all engineers do not. Skipping such a group outright left it
        // half-finished forever and the manager's engineer picker scoping to nobody. The existing
        // team is reused and topped up instead.
        Team handMade = teamRepository.save(new Team("Hand made", "an-admin"));
        AppUser manager = appUserRepository.findByEmailIgnoreCase("harika.velidi@cloudfuze.com").orElseThrow();
        manager.setTeam(handMade);
        appUserRepository.save(manager);

        // Detach that team's engineers, as if the admin never added them.
        List<AppUser> engineers = appUserRepository.findByTeamIdAndRole(
                teamRepository.findByNameIgnoreCase("Team 1").orElseThrow().getId(),
                AppUserRole.MIGRATION_ENGINEER);
        engineers.forEach(e -> e.setTeam(null));
        appUserRepository.saveAll(engineers);
        long teamsBefore = teamRepository.count();

        bootstrap.run();

        // No duplicate team, and the manager stays on the one the admin made.
        assertThat(teamRepository.count()).isEqualTo(teamsBefore);
        AppUser managerAfter = appUserRepository.findByEmailIgnoreCase("harika.velidi@cloudfuze.com").orElseThrow();
        assertThat(managerAfter.getTeam().getId()).isEqualTo(handMade.getId());

        // ...and the engineers the file names are now on it.
        assertThat(appUserRepository.findByTeamIdAndRole(handMade.getId(), AppUserRole.MIGRATION_ENGINEER))
                .extracting(AppUser::getEmail)
                .contains("siva.kota@cloudfuze.com", "ravi.hemanth@cloudfuze.com", "meena.lakshmi@cloudfuze.com");
    }
}
