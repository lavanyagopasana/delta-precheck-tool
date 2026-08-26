package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-level guard for {@link PmoSyncService}: the sync runs OUTSIDE a transaction
 * ({@code Propagation.NOT_SUPPORTED}, so a slow PMO fetch doesn't hold a DB connection), which means
 * every {@code Project} it loads is detached by the time {@code save()} merges it back.
 *
 * <p><b>The thing this test exists to catch:</b> {@code Project.engineerEmails} is an
 * {@code @ElementCollection}, and therefore LAZY. On a detached entity that collection is an
 * uninitialised proxy, and a careless merge can persist it as empty -- which would mean the
 * five-minute poll silently deleting every engineer a Migration Manager had assigned. That is data
 * loss with no error and no log line, so it gets a real database test rather than a mocked one.
 *
 * <p>The per-project devLeadEmail / qaLeadEmail fields this originally also guarded were removed from
 * Project on 2026-08-26; engineerEmails is now the only sync-adjacent collection at risk.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
// The `locations` half is NOT optional and NOT redundant with @ActiveProfiles: Spring Boot ranks
// file:./application.properties (the gitignored local-dev override sitting in backend/) ABOVE
// profile-specific classpath config, so without it this test silently runs against the REAL Postgres
// database instead of H2 -- and it calls deleteAll(). Same reasoning as EndpointCharacterizationTest.
@TestPropertySource(locations = "classpath:/application-test.properties", properties = {
        // Never let this test reach the real PMO API: the client is mocked below, and auto-sync is off
        // so the scheduled poll can't race the assertions.
        "pmo.auto-sync-enabled=false",
        "pmo.api-key=test-key",
        "pmo.base-url=http://localhost:1",
        "pmo.import-statuses=ACTIVE"
})
class PmoSyncPersistenceTest {

    @Autowired
    private PmoSyncService pmoSyncService;

    @Autowired
    private ProjectRepository projectRepository;

    @MockBean
    private PmoProjectClient pmoProjectClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * engineerEmails is a LAZY @ElementCollection, so reading it needs an open session. Reading it in a
     * fresh transaction AFTER the sync is also the honest check: it goes back to the database rather
     * than trusting an in-memory copy the sync might have left looking correct.
     */
    private Set<String> engineersInDb(Long projectId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                new LinkedHashSet<>(projectRepository.findById(projectId).orElseThrow().getEngineerEmails()));
    }

    private static PmoProjectDto pmoRecord(String externalId, String name) {
        PmoProjectDto dto = new PmoProjectDto();
        dto.setExternalId(externalId);
        dto.setName(name);
        dto.setStatus("ACTIVE");
        dto.setCustomerName("Rick Van Etten");
        dto.setManagerName("Harika");
        dto.setPhase("DELTA");
        dto.setMigrationTypes("Gmail - Gmail");
        return dto;
    }

    @BeforeEach
    void reset() {
        projectRepository.deleteAll();
    }

    @Test
    void aSyncDoesNotWipeEngineersAssignedToAnAlreadySyncedProject() {
        Project existing = new Project();
        existing.setName("akira");
        existing.setExternalId("ext-1");
        existing.setExternalManagerName("Harika");
        Set<String> engineers = new LinkedHashSet<>(List.of(
                "siva.kota@cloudfuze.com", "ravi.hemanth@cloudfuze.com", "meena.lakshmi@cloudfuze.com"));
        existing.setEngineerEmails(engineers);
        Long id = projectRepository.save(existing).getId();

        // PMO reports a changed phase, so the sync definitely writes this row.
        PmoProjectDto changed = pmoRecord("ext-1", "akira");
        changed.setPhase("FINAL_VALIDATION");
        Mockito.when(pmoProjectClient.fetchProjects()).thenReturn(List.of(changed));

        pmoSyncService.sync();

        assertThat(engineersInDb(id))
                .as("the 5-minute poll must not delete engineer assignments")
                .containsExactlyInAnyOrder(
                        "siva.kota@cloudfuze.com", "ravi.hemanth@cloudfuze.com", "meena.lakshmi@cloudfuze.com");
        // And it did write what it owns.
        assertThat(projectRepository.findById(id).orElseThrow().getExternalPhase()).isEqualTo("FINAL_VALIDATION");
    }

    @Test
    void anUnchangedPollAlsoLeavesEngineersAlone() {
        Project existing = new Project();
        existing.setName("legal soft");
        existing.setExternalId("ext-2");
        existing.setEngineerEmails(new LinkedHashSet<>(List.of("siva.kota@cloudfuze.com")));
        Long id = projectRepository.save(existing).getId();

        Mockito.when(pmoProjectClient.fetchProjects()).thenReturn(List.of(pmoRecord("ext-2", "legal soft")));

        pmoSyncService.sync();   // first run: writes the external_* fields
        pmoSyncService.sync();   // second run: unchanged path, still saves to bump externalSyncedAt

        assertThat(engineersInDb(id)).containsExactly("siva.kota@cloudfuze.com");
    }

    @Test
    void theUniqueExternalIdConstraintIsRealSoADoubleRunCannotDuplicate() {
        Mockito.when(pmoProjectClient.fetchProjects()).thenReturn(List.of(pmoRecord("ext-3", "vatica health")));

        pmoSyncService.sync();
        pmoSyncService.sync();

        assertThat(projectRepository.findAll()).hasSize(1);
        assertThat(projectRepository.findByExternalId("ext-3")).isPresent();
    }
}
