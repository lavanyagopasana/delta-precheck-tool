package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.dto.PmoSyncResultDto;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PmoSyncService} -- the rules that turn PMO's project feed into rows here.
 *
 * <p>The behaviours pinned down below are the ones derived from the real feed on 2026-08-26 and are
 * the ones most likely to be broken by a well-meaning simplification:
 * <ul>
 *   <li>only the configured statuses are imported (ACTIVE by default; 105 of PMO's 190 are COMPLETED),</li>
 *   <li>PMO's duplicate names are disambiguated by migration type rather than dropped,</li>
 *   <li>matching is by externalId, so a rename in PMO updates in place instead of duplicating,</li>
 *   <li>PMO's project manager is resolved to a real MIGRATION_MANAGER email, never stored raw.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PmoSyncServiceTest {

    @Mock
    private PmoProjectClient pmoProjectClient;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AppUserService appUserService;

    @Mock
    private TeamService teamService;

    /** The real MIGRATION_MANAGER roster (docs/seed/teams-roster.sql), so matching is tested for real. */
    private static final List<String> MANAGERS = List.of(
            "harika.velidi@cloudfuze.com",
            "raghu.yellani@cloudfuze.com",
            "sravan.kesaram@cloudfuze.com",
            "lakshmi.prasanna@cloudfuze.com",
            "abhishikth.yenugula@cloudfuze.com",
            "ajay.singh@cloudfuze.com",
            "abhishek.sakala@cloudfuze.com",
            "pranavi@cloudfuze.com");

    private PmoSyncService service;

    @BeforeEach
    void setUp() {
        service = new PmoSyncService(pmoProjectClient, projectRepository, appUserService, teamService);
        ReflectionTestUtils.setField(service, "importStatuses", "ACTIVE");
        ReflectionTestUtils.setField(service, "autoSyncEnabled", true);
        // Default: nothing already in the database, and save() hands the entity straight back.
        when(projectRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(projectRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        when(appUserService.emailsForRole(AppUserRole.MIGRATION_MANAGER)).thenReturn(MANAGERS);
        // No teams set up in these tests -- a resolved manager simply brings an empty engineer set.
        when(teamService.engineersOf(anyString())).thenReturn(new LinkedHashSet<>());
    }

    private static PmoProjectDto withManager(String id, String name, String pmoManager) {
        PmoProjectDto dto = record(id, name, "ACTIVE", "Gmail - Gmail");
        dto.setManagerName(pmoManager);
        return dto;
    }

    private static PmoProjectDto record(String id, String name, String status, String types) {
        PmoProjectDto dto = new PmoProjectDto();
        dto.setExternalId(id);
        dto.setName(name);
        dto.setStatus(status);
        dto.setMigrationTypes(types);
        dto.setCustomerName("Some Customer");
        dto.setManagerName("Harika");
        dto.setPhase("DELTA");
        return dto;
    }

    /** Every Project handed to save(), in call order. atLeast(0) so a test that saves nothing works too. */
    private List<Project> savedProjects() {
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void importsOnlyTheConfiguredStatuses() {
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-active", "vatica health", "ACTIVE", "Slack - Chat"),
                record("id-done", "afscott", "COMPLETED", "Gmail - Outlook"),
                record("id-hold", "on hold thing", "ON_HOLD", "Gmail - Gmail"),
                record("id-cancel", "cancelled thing", "CANCELLED", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getSkippedByStatusCount()).isEqualTo(3);
        assertThat(savedProjects()).singleElement()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("vatica health"));
    }

    @Test
    void blankStatusFilterImportsEverything() {
        ReflectionTestUtils.setField(service, "importStatuses", "");
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-a", "one", "ACTIVE", "Gmail - Gmail"),
                record("id-b", "two", "COMPLETED", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSkippedByStatusCount()).isZero();
    }

    @Test
    void uniqueNamesAreImportedExactlyAsPmoHasThem() {
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-1", "dynamo 6", "ACTIVE", "Slack - Chat")));

        service.sync();

        assertThat(savedProjects()).singleElement()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("dynamo 6"));
    }

    @Test
    void trailingWhitespaceInPmoNamesIsTrimmed() {
        // Three real PMO records have a trailing space, e.g. "botz limited ".
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-1", "botz limited ", "ACTIVE", "Gmail - Gmail")));

        service.sync();

        assertThat(savedProjects()).singleElement()
                .satisfies(p -> assertThat(p.getName()).isEqualTo("botz limited"));
    }

    @Test
    void duplicateNamesAreSeparatedByMigrationType() {
        // "akira" is three PMO projects for one customer, split Drive / Gmail / Chat.
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-drive", "akira", "ACTIVE", "MyDrive - MyDrive"),
                record("id-gmail", "akira", "ACTIVE", "Gmail - Gmail"),
                record("id-chat", "akira", "ACTIVE", "Chat - Chat")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getCreatedCount()).isEqualTo(3);
        assertThat(result.getErrors()).isEmpty();
        assertThat(savedProjects()).extracting(Project::getName)
                .containsExactlyInAnyOrder(
                        "akira (MyDrive - MyDrive)",
                        "akira (Gmail - Gmail)",
                        "akira (Chat - Chat)");
    }

    @Test
    void recordsIdenticalInNameAndTypeFallBackToAnIdSuffix() {
        // One duplicate group in the live feed collides on migrationTypes too. Both must still land --
        // silently dropping one would hide a live migration.
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("aaaaaaaa-1111", "twin", "ACTIVE", "Gmail - Gmail"),
                record("bbbbbbbb-2222", "twin", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        assertThat(savedProjects()).extracting(Project::getName)
                .containsExactlyInAnyOrder("twin (Gmail - Gmail)", "twin (bbbbbbbb)");
    }

    @Test
    void aRenameInPmoUpdatesTheSameProjectInsteadOfCreatingASecond() {
        Project existing = new Project();
        existing.setId(42L);
        existing.setExternalId("id-1");
        existing.setName("old name");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(existing));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-1", "new name", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getCreatedCount()).isZero();
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(savedProjects()).singleElement().satisfies(p -> {
            assertThat(p.getId()).isEqualTo(42L);
            assertThat(p.getName()).isEqualTo("new name");
        });
    }

    @Test
    void anUnchangedPollIsReportedAsUnchangedNotUpdated() {
        Project existing = new Project();
        existing.setId(7L);
        existing.setExternalId("id-1");
        existing.setName("steady");
        // Must already hold what "Harika" resolves to, or the poll legitimately changes something by
        // assigning the manager and this is no longer an unchanged run.
        existing.setMigrationManagerName("harika.velidi@cloudfuze.com");
        existing.setExternalCustomerName("Some Customer");
        existing.setExternalManagerName("Harika");
        existing.setExternalStatus("ACTIVE");
        existing.setExternalPhase("DELTA");
        existing.setExternalMigrationTypes("Gmail - Gmail");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(existing));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-1", "steady", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getUnchangedCount()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isZero();
    }

    @Test
    void pmoManagerIsResolvedToARealEmailNotStoredAsADisplayName() {
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("id-1", "legal soft", "Harika")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getManagersAssigned()).isEqualTo(1);
        assertThat(savedProjects()).singleElement().satisfies(p -> {
            // The display name is kept for context, but the assignable field holds a real address --
            // migrationManagerName is compared as an email by isVisible and the whole sign-off chain.
            assertThat(p.getExternalManagerName()).isEqualTo("Harika");
            assertThat(p.getMigrationManagerName()).isEqualTo("harika.velidi@cloudfuze.com");
            assertThat(p.getCreatedBy()).isEqualTo(PmoSyncService.SYNC_CREATED_BY);
        });
    }

    @Test
    void resolvesEveryShapeOfNameTheLiveFeedActuallyContains() {
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("a", "p1", "Harika"),            // first name only, unique
                withManager("b", "p2", "Abhishikth"),        // first name only, unique
                withManager("c", "p3", "Sravan"),            // first name only, unique
                withManager("d", "p4", "Ajay Singh"),        // full name -> ajay.singh
                withManager("e", "p5", "Raghu Yellani"),     // full name
                withManager("f", "p6", "Lakshmi prasanna"),  // full name, PMO's own casing
                withManager("g", "p7", "Abhishek Sakala"),   // must NOT collide with Abhishikth
                withManager("h", "p8", "Pranavi")));         // single-token local part

        PmoSyncResultDto result = service.sync();

        assertThat(result.getManagersAssigned()).isEqualTo(8);
        assertThat(result.getUnresolvedManagers()).isEmpty();
        assertThat(savedProjects()).extracting(Project::getMigrationManagerName)
                .containsExactly(
                        "harika.velidi@cloudfuze.com",
                        "abhishikth.yenugula@cloudfuze.com",
                        "sravan.kesaram@cloudfuze.com",
                        "ajay.singh@cloudfuze.com",
                        "raghu.yellani@cloudfuze.com",
                        "lakshmi.prasanna@cloudfuze.com",
                        "abhishek.sakala@cloudfuze.com",
                        "pranavi@cloudfuze.com");
    }

    @Test
    void aPmoManagerWithNoManagerAccountHereIsReportedNotGuessedAt() {
        // Live cases: Sriram Ramakrishnan and Chandra Mouli hold MIGRATION_ENGINEER accounts here
        // (assigning one would make the project unapprovable) and Nivas has no account at all.
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("a", "hcl", "Nivas"),
                withManager("b", "other", "Sriram Ramakrishnan")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getManagersAssigned()).isZero();
        assertThat(result.getUnresolvedManagers()).containsExactly("Nivas", "Sriram Ramakrishnan");
        assertThat(savedProjects()).allSatisfy(p -> assertThat(p.getMigrationManagerName()).isNull());
    }

    @Test
    void aMultiWordNameIsNeverMatchedOnItsFirstNameAlone() {
        // "Ajay Kumar" must not become ajay.singh@ -- that hands the project to the wrong approver.
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("a", "p", "Ajay Kumar")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getManagersAssigned()).isZero();
        assertThat(result.getUnresolvedManagers()).containsExactly("Ajay Kumar");
        assertThat(savedProjects()).singleElement()
                .satisfies(p -> assertThat(p.getMigrationManagerName()).isNull());
    }

    @Test
    void anAmbiguousFirstNameIsRefusedRatherThanPickedFrom() {
        when(appUserService.emailsForRole(AppUserRole.MIGRATION_MANAGER))
                .thenReturn(List.of("harika.velidi@cloudfuze.com", "harika.rao@cloudfuze.com"));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("a", "p", "Harika")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getManagersAssigned()).isZero();
        assertThat(result.getUnresolvedManagers()).containsExactly("Harika");
    }

    @Test
    void aManuallyAssignedManagerIsNotSilentlyOverwritten() {
        Project existing = new Project();
        existing.setId(5L);
        existing.setExternalId("id-1");
        existing.setName("legal soft");
        existing.setMigrationManagerName("someone.else@cloudfuze.com");  // chosen by a human here
        existing.setExternalManagerName("Harika");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(existing));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("id-1", "legal soft", "Ajay Singh")));

        PmoSyncResultDto result = service.sync();

        assertThat(savedProjects()).singleElement().satisfies(p ->
                assertThat(p.getMigrationManagerName()).isEqualTo("someone.else@cloudfuze.com"));
        assertThat(result.getManagersAssigned()).isZero();
        assertThat(result.getErrors()).singleElement()
                .satisfies(e -> assertThat(e).contains("Ajay Singh").contains("left unchanged"));
    }

    @Test
    void aManagerThePreviousSyncSetIsUpdatedWhenPmoChangesIt() {
        Project existing = new Project();
        existing.setId(6L);
        existing.setExternalId("id-1");
        existing.setName("legal soft");
        // Exactly what "Harika" resolves to, so the sync owns this field and nobody has touched it.
        existing.setMigrationManagerName("harika.velidi@cloudfuze.com");
        existing.setExternalManagerName("Harika");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(existing));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("id-1", "legal soft", "Ajay Singh")));

        PmoSyncResultDto result = service.sync();

        assertThat(savedProjects()).singleElement().satisfies(p ->
                assertThat(p.getMigrationManagerName()).isEqualTo("ajay.singh@cloudfuze.com"));
        assertThat(result.getManagersAssigned()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void anUnresolvableNameNeverClearsAnExistingAssignment() {
        Project existing = new Project();
        existing.setId(8L);
        existing.setExternalId("id-1");
        existing.setName("hcl");
        existing.setMigrationManagerName("harika.velidi@cloudfuze.com");
        existing.setExternalManagerName("Harika");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(existing));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                withManager("id-1", "hcl", "Nivas")));

        service.sync();

        assertThat(savedProjects()).singleElement().satisfies(p ->
                assertThat(p.getMigrationManagerName()).isEqualTo("harika.velidi@cloudfuze.com"));
    }

    @Test
    void aNameAlreadyTakenByAHandCreatedProjectIsSuffixedRatherThanHijacked() {
        Project handMade = new Project();
        handMade.setId(99L);
        handMade.setName("akira");
        // No externalId -- somebody created this here before the sync existed.
        when(projectRepository.findByNameIgnoreCase("akira")).thenReturn(Optional.of(handMade));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("cccccccc-3333", "akira", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(savedProjects()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("akira (cccccccc)");
            assertThat(p.getId()).isNull();  // a new row, not the hand-made one
            assertThat(p.getExternalId()).isEqualTo("cccccccc-3333");
        });
    }

    @Test
    void aRecordWithNoIdOrNoNameIsReportedAndSkippedWithoutFailingTheBatch() {
        PmoProjectDto noId = record(null, "nameless id", "ACTIVE", "Gmail - Gmail");
        PmoProjectDto noName = record("id-2", null, "ACTIVE", "Gmail - Gmail");
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                noId, noName, record("id-3", "good one", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getErrors()).hasSize(2);
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(savedProjects()).extracting(Project::getName).containsExactly("good one");
    }

    @Test
    void oneFailingRecordDoesNotAbortTheRest() {
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            if ("boom".equals(p.getName())) {
                throw new RuntimeException("constraint violation");
            }
            return p;
        });
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(
                record("id-1", "boom", "ACTIVE", "Gmail - Gmail"),
                record("id-2", "fine", "ACTIVE", "Gmail - Gmail")));

        PmoSyncResultDto result = service.sync();

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).contains("boom").contains("constraint violation");
    }

    @Test
    void aProjectCreatedInPmoAfterTheFirstSyncIsPickedUpByTheNextPollWithNoManualTrigger() {
        // The whole point of the feature: somebody creates a project in PMO and it turns up here on
        // its own. Poll 1 sees one project; PMO then gains a second; poll 2 must create it without
        // anybody pressing "Sync from PMO".
        when(pmoProjectClient.isConfigured()).thenReturn(true);
        PmoProjectDto first = withManager("id-1", "vatica health", "Ajay Singh");
        PmoProjectDto second = withManager("id-2", "brand new project", "Harika");

        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(first));
        service.scheduledSync();
        assertThat(savedProjects()).extracting(Project::getName).containsExactly("vatica health");

        // PMO now returns both. The already-synced one must be found by externalId (not re-created).
        Project alreadySynced = new Project();
        alreadySynced.setId(1L);
        alreadySynced.setExternalId("id-1");
        alreadySynced.setName("vatica health");
        alreadySynced.setExternalManagerName("Ajay Singh");
        alreadySynced.setExternalStatus("ACTIVE");
        alreadySynced.setExternalPhase("DELTA");
        alreadySynced.setExternalCustomerName("Some Customer");
        alreadySynced.setExternalMigrationTypes("Gmail - Gmail");
        alreadySynced.setMigrationManagerName("ajay.singh@cloudfuze.com");
        when(projectRepository.findByExternalId("id-1")).thenReturn(Optional.of(alreadySynced));
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(first, second));

        service.scheduledSync();

        // Across both polls: one save for the first project, then one untouched re-save plus the new one.
        assertThat(savedProjects()).extracting(Project::getName)
                .containsExactly("vatica health", "vatica health", "brand new project");
        assertThat(savedProjects().get(2).getExternalId()).isEqualTo("id-2");
        assertThat(savedProjects().get(2).getMigrationManagerName()).isEqualTo("harika.velidi@cloudfuze.com");
        assertThat(savedProjects().get(2).getCreatedBy()).isEqualTo(PmoSyncService.SYNC_CREATED_BY);
    }

    @Test
    void aProjectThatOnlyLaterBecomesActiveInPmoIsPickedUpWhenItDoes() {
        // Filtering to ACTIVE is not a permanent exclusion: a project sitting in ON_HOLD or COMPLETED
        // today arrives the moment PMO flips it to ACTIVE, on the next poll.
        when(pmoProjectClient.isConfigured()).thenReturn(true);
        PmoProjectDto onHold = record("id-9", "waking up", "ON_HOLD", "Gmail - Gmail");
        when(pmoProjectClient.fetchProjects()).thenReturn(List.of(onHold));

        service.scheduledSync();
        assertThat(savedProjects()).isEmpty();

        onHold.setStatus("ACTIVE");
        service.scheduledSync();

        assertThat(savedProjects()).extracting(Project::getName).containsExactly("waking up");
    }

    @Test
    void aSecondSyncIsRefusedWhileOneIsAlreadyRunningRatherThanRacingItself() {
        // Both callers would see findByExternalId() return empty and both would try to create the same
        // project; the loser hits the UNIQUE constraint on external_id and reports it as an error.
        when(pmoProjectClient.fetchProjects()).thenAnswer(invocation -> {
            // Re-entrant call standing in for the poll firing mid-way through an admin-triggered run.
            assertThatThrownBy(() -> service.sync())
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already running");
            return List.of(record("id-1", "only once", "ACTIVE", "Gmail - Gmail"));
        });

        service.sync();

        assertThat(savedProjects()).extracting(Project::getName).containsExactly("only once");
    }

    @Test
    void scheduledSyncDoesNothingWhenDisabledOrUnconfigured() {
        ReflectionTestUtils.setField(service, "autoSyncEnabled", false);
        when(pmoProjectClient.isConfigured()).thenReturn(true);

        service.scheduledSync();

        verify(pmoProjectClient, never()).fetchProjects();
    }

    @Test
    void scheduledSyncSwallowsFetchFailuresSoThePollKeepsRunning() {
        when(pmoProjectClient.isConfigured()).thenReturn(true);
        when(pmoProjectClient.fetchProjects()).thenThrow(new RuntimeException("PMO down"));

        service.scheduledSync();  // must not propagate -- a thrown exception would kill the schedule

        verify(projectRepository, never()).save(any(Project.class));
    }

    // ---- ingestOne: the single-record path the Delta-phase webhook uses (PmoWebhookService) --------

    @Test
    void ingestOneCreatesAProjectWithManagerAndEngineersResolvedJustLikeTheBatchPoll() {
        PmoProjectDto record = withManager("ext-1", "acme", "Harika");

        PmoSyncResultDto result = service.ingestOne(record);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(savedProjects()).singleElement().satisfies(p -> {
            assertThat(p.getExternalId()).isEqualTo("ext-1");
            assertThat(p.getMigrationManagerName()).isEqualTo("harika.velidi@cloudfuze.com");
            assertThat(p.getCreatedBy()).isEqualTo(PmoSyncService.SYNC_CREATED_BY);
        });
    }

    @Test
    void ingestOneUpdatesAnAlreadySyncedProjectMatchedByExternalId() {
        Project existing = new Project();
        existing.setId(9L);
        existing.setExternalId("ext-1");
        existing.setName("acme");
        when(projectRepository.findByExternalId("ext-1")).thenReturn(Optional.of(existing));

        PmoSyncResultDto result = service.ingestOne(withManager("ext-1", "acme", "Harika"));

        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getCreatedCount()).isZero();
        assertThat(savedProjects()).singleElement().satisfies(p -> assertThat(p.getId()).isEqualTo(9L));
    }

    @Test
    void ingestOneReportsAnErrorForAPayloadMissingIdOrNameRatherThanThrowing() {
        PmoProjectDto noId = record(null, "no id here", "ACTIVE", "Gmail - Gmail");

        PmoSyncResultDto result = service.ingestOne(noId);

        assertThat(result.getErrors()).isNotEmpty();
        verify(projectRepository, never()).save(any(Project.class));
    }
}
