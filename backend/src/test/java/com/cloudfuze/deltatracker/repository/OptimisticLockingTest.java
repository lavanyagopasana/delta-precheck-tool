package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.Ticket;
import com.cloudfuze.deltatracker.entity.TicketStatus;
import com.cloudfuze.deltatracker.entity.WorkspaceCombination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code @Version} column added to {@link Ticket} actually detects a stale write at the
 * persistence layer (STEP 8). The web-layer counterpart (TicketControllerTest) proves the resulting
 * exception is mapped to HTTP 409; this proves the exception is genuinely raised by two writers
 * racing on the same row rather than one silently clobbering the other.
 *
 * <p>A Ticket belongs to a WorkspaceCombination now, not a Server directly (see the per-combination
 * migration in decisions.md) -- the combination still needs a Server to hang off of, so both are
 * persisted here.
 *
 * <p>{@code Replace.NONE} means this test uses exactly the datasource the "test" profile configures
 * (H2) rather than Spring Boot's own auto-embedded replacement -- which makes it just as exposed as
 * every other {@code @SpringBootTest} here to a {@code file:./application.properties} in the working
 * directory silently outranking {@code application-test.properties}. {@code @TestPropertySource}
 * fixes that the same way it does elsewhere in this test suite.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(locations = "classpath:/application-test.properties")
class OptimisticLockingTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    void staleTicketWriteFailsOptimisticLock() {
        Server server = em.persist(new Server("SRV-OPT"));
        WorkspaceCombination combination = em.persist(new WorkspaceCombination(server, "Combo A"));
        Ticket ticket = em.persist(new Ticket(combination, "https://jira.example.com/browse/T-1", "eng@cloudfuze.com"));
        em.flush();
        Long id = ticket.getId();
        assertThat(ticket.getVersion()).isEqualTo(0L);
        // Detach our copy (version 0) from the context so it behaves like a request that read the row
        // and is now trying to write it back.
        em.clear();

        // Meanwhile another user commits a change that bumps the version behind our back.
        em.getEntityManager()
                .createNativeQuery("UPDATE tickets SET status = 'RESOLVED', version = version + 1 WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        // Our stale copy (still version 0) must NOT overwrite the newer row -- the write is rejected.
        ticket.setStatus(TicketStatus.RESOLVED);
        assertThatThrownBy(() -> ticketRepository.saveAndFlush(ticket))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
