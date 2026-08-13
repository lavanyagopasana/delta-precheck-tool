package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.config.CacheConfig;
import com.cloudfuze.deltatracker.dto.AppUserImportResultDto;
import com.cloudfuze.deltatracker.entity.AppUser;
import com.cloudfuze.deltatracker.entity.AppUserRole;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.exception.ResourceNotFoundException;
import com.cloudfuze.deltatracker.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppUserService}. Pure Mockito -- the repository and cache are mocked, so no
 * Spring context or database is needed. The @Cacheable read path is NOT exercised here (it only
 * activates behind the Spring proxy); what these lock in is the WRITE-side invariant: every mutation
 * clears the roster cache, including the importCsv -> upsert self-call that a proxy-based @CacheEvict
 * would silently miss.
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache rosterCache;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        // getCache is only reached on a write path; lenient so the guard-rejection tests (which throw
        // before evicting) don't trip Mockito's strict-stubbing check.
        lenient().when(cacheManager.getCache(CacheConfig.ROSTER_EMAILS_CACHE)).thenReturn(rosterCache);
        service = new AppUserService(repository, cacheManager);
    }

    @Test
    void upsertNewUserSavesAndEvictsCache() {
        when(repository.findByEmailIgnoreCase("new@cloudfuze.com")).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert("new@cloudfuze.com", AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com");

        verify(repository).save(any(AppUser.class));
        verify(rosterCache).clear();
    }

    @Test
    void upsertRejectsChangingOwnRole() {
        AppUser me = new AppUser("me@cloudfuze.com", AppUserRole.DEV_LEAD, "admin@cloudfuze.com");
        when(repository.findByEmailIgnoreCase("me@cloudfuze.com")).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> service.upsert("me@cloudfuze.com", AppUserRole.ADMIN, "me@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can't change your own role");

        verify(repository, never()).save(any());
        verify(rosterCache, never()).clear();
    }

    @Test
    void upsertRejectsDemotingLastAdmin() {
        AppUser lastAdmin = new AppUser("a@cloudfuze.com", AppUserRole.ADMIN, "seed");
        when(repository.findByEmailIgnoreCase("a@cloudfuze.com")).thenReturn(Optional.of(lastAdmin));
        when(repository.countByRole(AppUserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.upsert("a@cloudfuze.com", AppUserRole.DEV_LEAD, "other@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last remaining admin");

        verify(repository, never()).save(any());
    }

    /**
     * The regression lock the audit asked for: importCsv calls this.upsert directly (a self-call that
     * bypasses the Spring proxy), so eviction MUST be a plain method call, not @CacheEvict. Each
     * successful row's upsert clears the cache -- prove it fires per row.
     */
    @Test
    void importCsvEvictsCacheOnEverySuccessfulRow() {
        MockMultipartFile file = csv("email\nuser1@cloudfuze.com\nuser2@cloudfuze.com");
        when(repository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(repository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AppUserImportResultDto result = service.importCsv(file, AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com");

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        verify(rosterCache, times(2)).clear();
    }

    @Test
    void importCsvRejectsEmptyFile() {
        MockMultipartFile file = csv("");

        assertThatThrownBy(() -> service.importCsv(file, AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void importCsvCollectsPerRowErrorAndContinues() {
        MockMultipartFile file = csv("email\nnot-an-email\ngood@cloudfuze.com");
        when(repository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(repository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AppUserImportResultDto result = service.importCsv(file, AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com");

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        // Error names the 1-based line number of the bad row (header is line 1, bad row is line 2).
        assertThat(result.getErrors().get(0)).contains("Row 2");
    }

    @Test
    void removeRejectsRemovingSelf() {
        AppUser me = new AppUser("me@cloudfuze.com", AppUserRole.DEV_LEAD, "admin");
        when(repository.findByEmailIgnoreCase("me@cloudfuze.com")).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> service.remove("me@cloudfuze.com", "me@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("remove your own access");

        verify(repository, never()).delete(any());
    }

    @Test
    void removeRejectsLastAdmin() {
        AppUser lastAdmin = new AppUser("a@cloudfuze.com", AppUserRole.ADMIN, "seed");
        when(repository.findByEmailIgnoreCase("a@cloudfuze.com")).thenReturn(Optional.of(lastAdmin));
        when(repository.countByRole(AppUserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.remove("a@cloudfuze.com", "other@cloudfuze.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last remaining admin");

        verify(repository, never()).delete(any());
    }

    @Test
    void removeDeletesAndEvictsCache() {
        AppUser user = new AppUser("u@cloudfuze.com", AppUserRole.DEV_LEAD, "admin");
        when(repository.findByEmailIgnoreCase("u@cloudfuze.com")).thenReturn(Optional.of(user));

        service.remove("u@cloudfuze.com", "admin@cloudfuze.com");

        verify(repository).delete(user);
        verify(rosterCache).clear();
    }

    @Test
    void removeUnknownUserThrowsNotFound() {
        when(repository.findByEmailIgnoreCase("ghost@cloudfuze.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove("ghost@cloudfuze.com", "admin@cloudfuze.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void roleOfNullEmailReturnsEmpty() {
        assertThat(service.roleOf(null)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Per-row "role" column. Before this, one import applied a single role to every row, so onboarding
    // a team of mixed roles meant a separate file and a separate import per role.
    // ---------------------------------------------------------------------------------------------

    @Test
    void importCsvAppliesPerRowRoleFromRoleColumn() {
        MockMultipartFile file = csv("email,role\nmgr@cloudfuze.com,MIGRATION_MANAGER\ndev@cloudfuze.com,DEV_LEAD\nqa@cloudfuze.com,QA_LEAD");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com");

        assertThat(result.getCreatedCount()).isEqualTo(3);
        assertThat(result.getErrors()).isEmpty();
        assertThat(savedRoleFor("mgr@cloudfuze.com")).isEqualTo(AppUserRole.MIGRATION_MANAGER);
        assertThat(savedRoleFor("dev@cloudfuze.com")).isEqualTo(AppUserRole.DEV_LEAD);
        assertThat(savedRoleFor("qa@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
    }

    // People fill these files in from what the UI shows them ("Dev Lead"), not the enum constant, so
    // accepting only MIGRATION_MANAGER-style values would reject the most likely input.
    @Test
    void importCsvAcceptsFriendlyRoleLabelsAndAnyCase() {
        MockMultipartFile file = csv("email,role\na@cloudfuze.com,Migration Manager\nb@cloudfuze.com,dev lead\nc@cloudfuze.com,  QA Lead  \nd@cloudfuze.com,admin");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, null, "admin@cloudfuze.com");

        assertThat(result.getErrors()).isEmpty();
        assertThat(savedRoleFor("a@cloudfuze.com")).isEqualTo(AppUserRole.MIGRATION_MANAGER);
        assertThat(savedRoleFor("b@cloudfuze.com")).isEqualTo(AppUserRole.DEV_LEAD);
        assertThat(savedRoleFor("c@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
        assertThat(savedRoleFor("d@cloudfuze.com")).isEqualTo(AppUserRole.ADMIN);
    }

    @Test
    void importCsvFallsBackToDefaultRoleForBlankRoleCell() {
        MockMultipartFile file = csv("email,role\nlead@cloudfuze.com,DEV_LEAD\nplain@cloudfuze.com,");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, AppUserRole.MIGRATION_ENGINEER, "admin@cloudfuze.com");

        assertThat(result.getErrors()).isEmpty();
        assertThat(savedRoleFor("lead@cloudfuze.com")).isEqualTo(AppUserRole.DEV_LEAD);
        assertThat(savedRoleFor("plain@cloudfuze.com")).isEqualTo(AppUserRole.MIGRATION_ENGINEER);
    }

    // Column order must not matter -- both are located by header name, not position.
    @Test
    void importCsvHandlesRoleColumnBeforeEmailColumn() {
        MockMultipartFile file = csv("role,email\nQA_LEAD,qa@cloudfuze.com");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, null, "admin@cloudfuze.com");

        assertThat(result.getErrors()).isEmpty();
        assertThat(savedRoleFor("qa@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
    }

    // A typo'd role is that row's problem, not the file's -- every other row must still land.
    @Test
    void importCsvReportsUnknownRoleAsRowErrorAndKeepsGoing() {
        MockMultipartFile file = csv("email,role\ngood@cloudfuze.com,DEV_LEAD\ntypo@cloudfuze.com,Dev Leed\nalsogood@cloudfuze.com,QA_LEAD");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, null, "admin@cloudfuze.com");

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).contains("Row 3").contains("Dev Leed").contains("Accepted roles");
        assertThat(savedRoleFor("good@cloudfuze.com")).isEqualTo(AppUserRole.DEV_LEAD);
        assertThat(savedRoleFor("alsogood@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
    }

    // No role cell AND no default: report the row rather than silently guessing a role for someone.
    @Test
    void importCsvReportsRowWithNeitherRoleNorDefault() {
        MockMultipartFile file = csv("email,role\nnorole@cloudfuze.com,");

        AppUserImportResultDto result = service.importCsv(file, null, "admin@cloudfuze.com");

        assertThat(result.getCreatedCount()).isZero();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).contains("no role given").contains("norole@cloudfuze.com");
        verify(repository, never()).save(any(AppUser.class));
    }

    // Files written before the role column existed must keep importing exactly as they did.
    @Test
    void importCsvWithoutRoleColumnStillUsesDefaultForEveryRow() {
        MockMultipartFile file = csv("email\none@cloudfuze.com\ntwo@cloudfuze.com");
        stubAllNew();

        AppUserImportResultDto result = service.importCsv(file, AppUserRole.QA_LEAD, "admin@cloudfuze.com");

        assertThat(result.getCreatedCount()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        assertThat(savedRoleFor("one@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
        assertThat(savedRoleFor("two@cloudfuze.com")).isEqualTo(AppUserRole.QA_LEAD);
    }

    private void stubAllNew() {
        when(repository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(repository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Last role actually persisted for an email, read back off the save() captor. */
    private AppUserRole savedRoleFor(String email) {
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .map(AppUser::getRole)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no save captured for " + email));
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "roster.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }
}
