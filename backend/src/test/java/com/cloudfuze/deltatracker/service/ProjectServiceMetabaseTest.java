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
 * <p>The rule in one line: the project's Migration Manager or an assigned engineer may make the FIRST
 * choice; every change after that is admin-only.
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

    @InjectMocks private ProjectService projectService;

    private Project project;
    // Stands in for the project_metabase_databases table: the service reads through
    // findByProjectIdAndProductType and writes through save/delete, so one slot per product type is
    // enough to exercise the lock without a real database.
    private final java.util.Map<ProductType, ProjectMetabaseDatabase> stored = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        project = new Project("Acme Migration", "PMO sync", MANAGER, Set.of(ENGINEER));
        project.setId(1L);
        stored.clear();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        when(projectMetabaseDatabaseRepository.findByProjectIdAndProductType(any(), any()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(1))));
        when(projectMetabaseDatabaseRepository.save(any(ProjectMetabaseDatabase.class)))
                .thenAnswer(i -> {
                    ProjectMetabaseDatabase row = i.getArgument(0);
                    stored.put(row.getProductType(), row);
                    return row;
                });
        org.mockito.Mockito.doAnswer(i -> {
            stored.remove(((ProjectMetabaseDatabase) i.getArgument(0)).getProductType());
            return null;
        }).when(projectMetabaseDatabaseRepository).delete(any(ProjectMetabaseDatabase.class));
    }

    /** What is stored for MESSAGE right now, or null. */
    private String saved() {
        ProjectMetabaseDatabase row = stored.get(ProductType.MESSAGE);
        return row == null ? null : row.getDatabaseName();
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

    // --- the lock: every change after the first is admin-only -------------------------------------

    @Test
    void aNonAdminCannotChangeItOnceSet() {
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> set("something else", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin can change it")
                .hasMessageContaining("acme content");
        // Unchanged, not partially applied.
        assertThat(saved()).isEqualTo("acme content");
    }

    @Test
    void theEngineerWhoSetItCannotChangeItEither() {
        set("acme content", ENGINEER, AppUserRole.MIGRATION_ENGINEER);

        assertThatThrownBy(() -> set("something else", ENGINEER, AppUserRole.MIGRATION_ENGINEER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin can change it");
    }

    @Test
    void aNonAdminCannotClearItOnceSet() {
        // Clearing is a change like any other -- otherwise the lock is one blank submit away from
        // being reset and then re-set to anything.
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> set("", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin can change it");
        assertThat(saved()).isEqualTo("acme content");
    }

    @Test
    void reSubmittingTheSameValueIsNotTreatedAsAChange() {
        // A double-click or a stale page shouldn't produce a 409 -- nothing is actually changing.
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(saved()).isEqualTo("acme content");
    }

    @Test
    void anAdminCanChangeItAfterItIsSet() {
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        set("corrected database", "admin@cloudfuze.com", AppUserRole.ADMIN);

        assertThat(saved()).isEqualTo("corrected database");
    }

    @Test
    void theConflictIsA409NotA403() {
        // The frontend distinguishes them: 403 means "not your project", 409 means "already fixed,
        // ask an admin". Showing the wrong one sends people to the wrong person.
        set("acme content", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> set("something else", MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- the lock is PER PRODUCT TYPE, not per project ---------------------------------------------

    @Test
    void eachProductTypeIsLockedIndependently() {
        // A Metabase database only ever holds one product type's data, so a project spanning types
        // needs one name per type -- and fixing one must not fix or block the others.
        projectService.setMetabaseDatabase(1L, request("bakktmsg", ProductType.MESSAGE),
                MANAGER, AppUserRole.MIGRATION_MANAGER);

        // MESSAGE is now locked...
        assertThatThrownBy(() -> projectService.setMetabaseDatabase(1L,
                request("something else", ProductType.MESSAGE), MANAGER, AppUserRole.MIGRATION_MANAGER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an admin can change it");

        // ...but CONTENT is untouched and can still be set by the same non-admin.
        projectService.setMetabaseDatabase(1L, request("bakkt", ProductType.CONTENT),
                MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(stored.get(ProductType.MESSAGE).getDatabaseName()).isEqualTo("bakktmsg");
        assertThat(stored.get(ProductType.CONTENT).getDatabaseName()).isEqualTo("bakkt");
    }

    @Test
    void theLockMessageNamesTheProductTypeItAppliesTo() {
        // With up to three databases on one project, "already set" is ambiguous without the type.
        projectService.setMetabaseDatabase(1L, request("bakktemail", ProductType.EMAIL),
                MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThatThrownBy(() -> projectService.setMetabaseDatabase(1L,
                request("other", ProductType.EMAIL), MANAGER, AppUserRole.MIGRATION_MANAGER))
                .hasMessageContaining("EMAIL")
                .hasMessageContaining("bakktemail");
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
    void blankIsStoredAsNullRatherThanEmptyString() {
        // The frontend reads null as "not set yet"; an empty string would render as set-but-nameless.
        set("   ", MANAGER, AppUserRole.MIGRATION_MANAGER);

        assertThat(saved()).isNull();
    }

    @Test
    void withAuthDisabledTheLockDoesNotApply() {
        // callerEmail == null means auth isn't configured and the whole app runs open (see isVisible).
        // The lock would otherwise make local dev unusable.
        set("acme content", null, null);
        set("something else", null, null);

        assertThat(saved()).isEqualTo("something else");
    }
}
