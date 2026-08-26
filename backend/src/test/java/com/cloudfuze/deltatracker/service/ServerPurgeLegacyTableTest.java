package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.entity.PairStatus;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the 500 that made project deletion, server deletion and decommissioning all
 * impossible -- for admins included.
 *
 * <p>{@link ServerPurgeService} ends every purge with a raw
 * {@code DELETE FROM escalations WHERE server_id = ?}. Nothing maps that table, so ddl-auto=update
 * never creates it; it exists only in databases old enough to predate the Escalation -> Ticket
 * rename. On every database created since -- including the PostgreSQL one this app was migrated onto
 * -- the statement failed with {@code relation "escalations" does not exist}, a
 * BadSqlGrammarException that no GlobalExceptionHandler case matches, so it surfaced as a generic
 * 500 "Something went wrong. Please try again." with nothing naming the real cause.
 *
 * <p>Why the existing 207 tests all passed through this: {@code ServerPurgeServiceTest} MOCKS
 * JdbcTemplate, and a mock accepts {@code update(...)} happily and returns 0. This test uses the
 * REAL JdbcTemplate against the H2 test database, which -- being created by ddl-auto, exactly like
 * production -- has no {@code escalations} table either. That is the whole point: mocking the thing
 * that fails is what hid a broken destructive path behind a green suite.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(locations = "classpath:/application-test.properties")
class ServerPurgeLegacyTableTest {

    @Autowired
    private ServerPurgeService serverPurgeService;

    @Autowired
    private ServerRepository serverRepository;

    @Autowired
    private WorkspaceCombinationRepository workspaceCombinationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Server persistServerWithCombination(String name) {
        Server server = new Server(name);
        server.setStatus(PairStatus.PENDING);
        Server saved = serverRepository.save(server);

        WorkspaceCombination combination = new WorkspaceCombination();
        combination.setServer(saved);
        combination.setName("Teams to Slack");
        combination.setStatus(PairStatus.PENDING);
        workspaceCombinationRepository.save(combination);

        return saved;
    }

    @Test
    void purgeSucceedsWhenTheLegacyEscalationsTableDoesNotExist() {
        // Guard: if this ever starts existing, the test below stops proving anything.
        assertThat(legacyTableCount()).isZero();

        Server server = persistServerWithCombination("https://purge-no-legacy-table.example.com");
        Long serverId = server.getId();

        // Pre-fix this threw BadSqlGrammarException -> 500 "Something went wrong. Please try again."
        assertThatCode(() -> serverPurgeService.purge(server)).doesNotThrowAnyException();

        assertThat(serverRepository.findById(serverId)).isEmpty();
        assertThat(workspaceCombinationRepository.findByServerId(serverId)).isEmpty();
    }

    @Test
    void purgeStillClearsLegacyEscalationRowsOnAnOlderDatabaseThatHasThem() {
        // The guard must not become "silently skip the cleanup". On a database that DOES carry the
        // legacy table, a leftover row still blocks deleting the server it points at, so the DELETE
        // has to keep running. Recreated here to stand in for a pre-rename database.
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS escalations (id BIGINT PRIMARY KEY, server_id BIGINT)");
        try {
            Server server = persistServerWithCombination("https://purge-with-legacy-table.example.com");
            Long serverId = server.getId();
            jdbcTemplate.update("INSERT INTO escalations (id, server_id) VALUES (?, ?)", 9001, serverId);

            // A fresh instance, because the real service caches the table-existence answer and the
            // autowired singleton may already have cached "absent" from the test above.
            ServerPurgeService freshPurge = freshPurgeService();
            assertThatCode(() -> freshPurge.purge(server)).doesNotThrowAnyException();

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM escalations WHERE server_id = ?", Integer.class, serverId);
            assertThat(remaining).isZero();
            assertThat(serverRepository.findById(serverId)).isEmpty();
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS escalations");
        }
    }

    private Integer legacyTableCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'escalations'",
                Integer.class);
    }

    // The cached existence flag is per-instance, so a second purge against a now-different schema
    // needs its own instance. Built from the same collaborators the container wired.
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private ServerPurgeService freshPurgeService() {
        return applicationContext.getAutowireCapableBeanFactory().createBean(ServerPurgeService.class);
    }
}
