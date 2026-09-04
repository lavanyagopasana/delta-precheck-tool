package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.ProjectMetabaseRequest;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.ProjectMetabaseDatabase;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.PreCheckSubmissionRepository;
import com.cloudfuze.deltatracker.repository.ProjectMetabaseDatabaseRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.SignOffRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Metabase-database rule on {@link ProjectService}, which is a permission boundary rather than a
 * piece of display logic: whoever fixes the database decides where the processed/conflict figures a
 * Delta gets approved against come from.
 *
 * <p>The rule in one line: the project's Migration Manager or an assigned engineer may ADD a
 * database to any product type -- several per type is normal -- while REMOVING one is admin-only.
 * The asymmetry is the point. Adding widens the figures and the new database is listed on the page
 * where anyone can see it contributed; removing silently shrinks the numbers a Delta was approved
 * against.
 *
 * <p>Lenient stubbing because {@code buildSummary} touches most of the injected repositories on the
 * way out and Mockito's defaults (empty lists) already satisfy them -- the assertions here are about
 * the rule, not about the summary it happens to return.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceMetabaseTest {

    private static final String MANAGER = "harika.velidi@cloudfuze.com";
    private static final String ENGINEER = "pravallika.punumalli@cloudfuze.com";
    private static final String OUTSIDER = "someone.else@cloudfuze.com";

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMetabaseDatabaseRepository projectMetabaseDatabaseRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private WorkspacePairRepository workspacePairRepository;
    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private SignOffRepository signOffRepository;
    @Mock private PreCheckSubmissionRepository preCheckSubmissionRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private ServerService serverService;
    @Mock private WorkspaceCombinationService workspaceCombinationService;
    @Mock private AppUserService appUserService;
    @Mock private ServerPurgeService serverPurgeService;
    @Mock private TeamService teamService;

    @InjectMocks private ProjectService projectService;

    private Project project;
    // Stands in for the project_metabase_databases table. A LIST, not one slot per product type:
    // the whole point of this change is that a type can hold several databases, and a map keyed by
    // type would silently overwrite rather than reproduce that.
    private final java.util.List<ProjectMetabaseDatabase> stored = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        project = new Project("Acme Migration", "PMO sync", MANAGER, Set.of(ENGINEER));
        project.setId(1L);
        stored.clear();
        // canEditMetabaseDatabase's engineer branch checks LIVE team membership now (TeamService),
        // not the Project.engineerEmails snapshot -- ENGINEER stands in for "on MANAGER's team".
        when(teamService.isCurrentlyOnManagersTeam(MANAGER, ENGINEER)).thenReturn(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        when(projectMetabaseDatabaseRepository.findByProjectIdAndProductType(any(), any()))
                .thenAnswer(i -> namesOf(i.getArgument(1)));
        // Case-insensitive, mirroring the real derived query -- "Bakkt" beside "bakkt" would double
        // that product type's figures rather than reading as the duplicate it is.
        when(projectMetabaseDatabaseRepository
                .findByProjectIdAndProductTypeAndDatabaseNameIgnoreCase(any(), any(), any()))
                .thenAnswer(i -> stored.stream()
                        .filter(r -> r.getProductType() == i.getArgument(1)
                                && r.getDatabaseName().equalsIgnoreCase(i.getArgument(2)))
                        .findFirst());
        when(projectMetabaseDatabaseRepository.save(any(ProjectMetabaseDatabase.class)))
                .thenAnswer(i -> {
                    ProjectMetabaseDatabase row = i.getArgument(0);
                    stored.add(row);
                    return row;
                });
        org.mockito.Mockito.doAnswer(i -> {
            stored.remove((ProjectMetabaseDatabase) i.getArgument(0));
            return null;
        }).when(projectMetabaseDatabaseRepository).delete(any(ProjectMetabaseDatabase.class));
    }

    /** Every database stored for a product type right now, in insertion order. */
    private java.util.List<ProjectMetabaseDatabase> namesOf(ProductType type) {
        return stored.stream().filter(r -> r.getProductType() == type).toList();
    }

    /** The database names stored for MESSAGE right now. */
    private java.util.List<String> savedNames() {
        return namesOf(ProductType.MESSAGE).stream()
                .map(ProjectMetabaseDatabase::getDatabaseName).toList();
    }

    /** The single MESSAGE database, or null -- for the cases that only ever add one. */
    private String saved() {
        java.util.List<String> names = savedNames();
        return names.isEmpty() ? null : names.get(0);
    }

    private void remove(String name, String email, AppUserRole role) {
        projectService.removeMetabaseDatabase(1L, ProductType.MESSAGE.name(), name, email, role);
    }

    private ProjectMetabaseRequest request(String name) {
        return request(name, ProductType.MESSAGE);
    }

    private ProjectMetabaseRequest request(String name, ProductType type) {
        ProjectMetabaseRequest r = new ProjectMetabaseRequest();
        r.setProductType(type.name());
        r.setDatabaseName(name);
        return r;
    }

    private void set(String name, String email, AppUserRole role) {
        projectService.setMetabaseDatabase(1L, request(name), email, role);
    }

    // --- the first choice: manager OR engineer, per the product decision ---------------------------

    @Test
    void theProjectsMigrationManagerCanMakeTheFirstChoice() {
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(saved()).isEqualTo("acme content");
    }

    @Test
    void anAssignedEngineerCanMakeTheFirstChoice() {
        set("acme content", ENGINEER, AppUserRole.MIGRATION_ENGINEER);

        assertThat(saved()).isEqualTo("acme content");
    }

    @Test
    void aManagerOfSomeOtherProjectCannot() {
        assertThatThrownBy(() -> set("acme content", OUTSIDER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can set its Metabase database");
        verify(projectMetabaseDatabaseRepository, never()).save(any());
    }

    @Test
    void anUnassignedEngineerCannot() {
        assertThatThrownBy(() -> set("acme content", OUTSIDER, AppUserRole.MIGRATION_ENGINEER))
                .isInstanceOf(ApiException.class)
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theApproversCannotSetItEvenThoughTheyCanSeeIt() {
        // DEV_LEAD/QA_LEAD approve against these figures -- letting an approver choose the source
        // would collapse the same two-person split the sign-off chain exists to enforce.
        for (AppUserRole approver : new AppUserRole[] {AppUserRole.DEV_LEAD, AppUserRole.QA_LEAD}) {
            assertThatThrownBy(() -> set("acme content", "lead@cloudfuze.com", approver))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("can set its Metabase database");
        }
    }

    // --- several databases per product type -------------------------------------------------------

    @Test
    void aProductTypeCanHoldMoreThanOneDatabase() {
        // The whole point of this change: one customer engagement can spread a single product type
        // across several Metabase databases, and every one of them has to be counted.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);
        set("bakkt2", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(savedNames()).containsExactly("bakkt", "bakkt2");
    }

    @Test
    void anAssignedEngineerCanAddASecondDatabaseToo() {
        // Adding is NOT admin-gated. It widens the figures and the new database is listed on the
        // page where anyone can see it contributed -- it is removal that hides things.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        set("bakkt2", ENGINEER, AppUserRole.MIGRATION_ENGINEER);

        assertThat(savedNames()).containsExactly("bakkt", "bakkt2");
    }

    @Test
    void theSameDatabaseCannotBeAddedTwice() {
        // Not silently de-duplicated: adding the same database twice would double every figure for
        // that product type, so what the caller asked for cannot be what they meant.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already one of this project's")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(savedNames()).containsExactly("bakkt");
    }

    @Test
    void theDuplicateCheckIsCaseInsensitive() {
        // Metabase's own names are case-insensitive, so "Bakkt" beside "bakkt" is the same database
        // twice -- and would double that product type's figures rather than read as a duplicate.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> set("BAKKT", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void productTypesDoNotShareTheirDatabaseLists() {
        // A Metabase database only ever holds one product type's data, so adding to one type must
        // not touch another -- and the same name under a different type is a different database.
        projectService.setMetabaseDatabase(1L, request("bakktmsg", ProductType.MESSAGE),
                MANAGER, AppUserRole.MIGRATION_MANAGER);
        projectService.setMetabaseDatabase(1L, request("bakkt", ProductType.CONTENT),
                MANAGER, AppUserRole.MIGRATION_MANAGER);
        projectService.setMetabaseDatabase(1L, request("bakkt2", ProductType.CONTENT),
                MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(namesOf(ProductType.MESSAGE)).hasSize(1);
        assertThat(namesOf(ProductType.CONTENT)).hasSize(2);
    }

    // --- removing is admin-only -------------------------------------------------------------------

    @Test
    void anAdminCanRemoveOneDatabaseLeavingTheRest() {
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);
        set("bakkt2", MANAGER, AppUserRole.MIGRATION_MANAGER);

        remove("bakkt", "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(savedNames()).containsExactly("bakkt2");
    }

    @Test
    void aMigrationManagerCanRemoveADatabase() {
        // Managers own delivery for their projects, so they hold the destructive project actions
        // alongside admins. Removing still subtracts from the processed and conflict counts a Delta
        // was already approved against -- which is why it stays closed to engineers below.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        remove("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(savedNames()).isEmpty();
    }

    @Test
    void theEngineerWhoAddedItCannotRemoveItEither() {
        // Having added it grants no special claim over removing it -- an engineer may still add.
        set("bakkt", ENGINEER, AppUserRole.MIGRATION_ENGINEER);

        assertThatThrownBy(() -> remove("bakkt", ENGINEER, AppUserRole.MIGRATION_ENGINEER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin or a Migration Manager can remove")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Unchanged, not partially applied.
        assertThat(savedNames()).containsExactly("bakkt");
    }

    @Test
    void removingSomethingThatWasNeverAddedIsA404() {
        // Distinguished from a 403 on purpose: "you may not do this" and "that isn't here" send the
        // reader to two different places.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> remove("never-added", "admin@cloudfuze.com", AppUserRole.ADMIN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingIsCaseInsensitiveOnTheName() {
        // Matches how the name was matched on the way in, so a name copied back with different
        // casing still finds its row rather than 404ing.
        set("bakkt", MANAGER, AppUserRole.MIGRATION_MANAGER);

        remove("BAKKT", "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(savedNames()).isEmpty();
    }

    @Test
    void removalIsAllowedWhenAuthIsOff() {
        // callerEmail == null means auth isn't configured and the whole app runs open (see
        // isVisible) -- the admin gate would otherwise make local dev unusable.
        set("bakkt", null, null);

        remove("bakkt", null, null);

        assertThat(savedNames()).isEmpty();
    }

    @Test
    void anUnknownProductTypeIsRejectedAsABadRequest() {
        ProjectMetabaseRequest bad = new ProjectMetabaseRequest();
        bad.setProductType("SHAREPOINT");
        bad.setDatabaseName("whatever");

        assertThatThrownBy(() -> projectService.setMetabaseDatabase(1L, bad, MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expected MESSAGE, EMAIL or CONTENT")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void productTypeIsAcceptedCaseInsensitively() {
        ProjectMetabaseRequest lower = new ProjectMetabaseRequest();
        lower.setProductType("message");
        lower.setDatabaseName("bakktmsg");

        projectService.setMetabaseDatabase(1L, lower, MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(saved()).isEqualTo("bakktmsg");
    }

    // --- blanks and auth-off ----------------------------------------------------------------------

    @Test
    void aBlankNameIsRejectedRatherThanClearingTheType() {
        // Blank used to mean "clear this product type". With several databases per type there is
        // nothing unambiguous for it to clear, so removal is now its own explicit call and a blank
        // add is simply a bad request -- silently clearing a list on an empty submit would be the
        // worst reading of it.
        assertThatThrownBy(() -> set("   ", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a Metabase database to add")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(savedNames()).isEmpty();
    }

    @Test
    void withAuthDisabledDatabasesStillAccumulateRatherThanReplace() {
        // callerEmail == null means auth isn't configured and the whole app runs open (see
        // isVisible), so neither the add permission nor the admin-only removal gate applies.
        //
        // What it must NOT do is fall back to the old replace behaviour: a second add appends, the
        // same as it does with auth on. Local dev and production have to agree about what the
        // figures mean.
        set("acme content", null, null);
        set("something else", null, null);

        assertThat(savedNames()).containsExactly("acme content", "something else");
    }
}
