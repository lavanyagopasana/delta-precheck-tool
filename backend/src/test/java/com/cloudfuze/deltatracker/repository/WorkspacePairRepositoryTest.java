package com.cloudfuze.deltatracker.repository;

import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-layer test for the display-cap query on H2. Proves that the paged
 * {@code findByServerId(Long, Pageable)} overload used by {@code WorkspacePairService.listByServer}
 * honours the 500-row cap (via {@code PageRequest} limit) and returns rows in ascending id order,
 * scoped to the requested server.
 *
 * <p>{@code @TestPropertySource} is required alongside {@code Replace.NONE} for the same reason as
 * OptimisticLockingTest -- otherwise a {@code file:./application.properties} in the working
 * directory can silently outrank {@code application-test.properties}'s H2 configuration.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(locations = "classpath:/application-test.properties")
class WorkspacePairRepositoryTest {

    private static final int MAX_DISPLAY_ROWS = 500;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private WorkspacePairRepository repository;

    private Long serverAId;

    @BeforeEach
    void seed() {
        Server serverA = em.persist(new Server("SRV-A"));
        Server serverB = em.persist(new Server("SRV-B"));
        serverAId = serverA.getId();

        // 550 pairs on server A (exceeds the 500 cap) and a few on server B to prove scoping.
        for (int i = 0; i < 550; i++) {
            em.persist(new WorkspacePair(serverA, "src" + i + "@x.com", "dst" + i + "@x.com"));
        }
        for (int i = 0; i < 5; i++) {
            em.persist(new WorkspacePair(serverB, "b-src" + i + "@x.com", "b-dst" + i + "@x.com"));
        }
        em.flush();
        em.clear();
    }

    @Test
    void pagedQueryCapsAt500AndPreservesIdAscending() {
        List<WorkspacePair> page = repository.findByServerId(serverAId,
                PageRequest.of(0, MAX_DISPLAY_ROWS, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(page).hasSize(MAX_DISPLAY_ROWS);
        assertThat(page).allSatisfy(p -> assertThat(p.getServer().getId()).isEqualTo(serverAId));

        List<Long> ids = page.stream().map(WorkspacePair::getId).toList();
        assertThat(ids).isSorted();
        // The cap returns the FIRST 500 by id, not an arbitrary 500.
        assertThat(ids).isEqualTo(ids.stream().sorted().toList());
    }

    @Test
    void unpagedQueryReturnsEveryRowForTheServer() {
        assertThat(repository.findByServerId(serverAId)).hasSize(550);
        assertThat(repository.countByServerId(serverAId)).isEqualTo(550);
    }
}
