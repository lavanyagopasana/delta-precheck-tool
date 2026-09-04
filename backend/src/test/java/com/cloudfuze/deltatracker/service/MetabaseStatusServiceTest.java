package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.MetabaseDatabaseDto;
import com.cloudfuze.deltatracker.dto.MetabaseStatusCountDto;
import com.cloudfuze.deltatracker.dto.MetabaseStatusDto;
import com.cloudfuze.deltatracker.entity.ProductType;
import com.cloudfuze.deltatracker.entity.Project;
import com.cloudfuze.deltatracker.entity.ProjectMetabaseDatabase;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.ProjectMetabaseDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MetabaseStatusService} -- the per-product-type processStatus breakdown.
 *
 * <p>The theme of these tests is that <b>no failure may become a zero</b>. An unset database, an
 * unreachable Metabase, a database name Metabase doesn't know and a genuinely empty collection are
 * four different things; collapsing any into "0 processed" would read on screen as "no migration has
 * happened", which is the worst possible wrong answer for a figure a Delta gets approved against.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetabaseStatusServiceTest {

    @Mock private ProjectMetabaseDatabaseRepository repository;
    @Mock private MetabaseClient metabaseClient;

    @InjectMocks private MetabaseStatusService service;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("Acme", "PMO sync", "mm@cloudfuze.com", null);
        project.setId(1L);
    }

    private void configured(ProductType type, String databaseName) {
        List<ProjectMetabaseDatabase> rows = new java.util.ArrayList<>(repository.findByProjectId(1L));
        rows.add(new ProjectMetabaseDatabase(project, type, databaseName, "someone@cloudfuze.com"));
        when(repository.findByProjectId(1L)).thenReturn(rows);
    }

    private MetabaseDatabaseDto db(long id, String name) {
        MetabaseDatabaseDto dto = new MetabaseDatabaseDto();
        dto.setId(id);
        dto.setName(name);
        dto.setEngine("mongo");
        return dto;
    }

    /** The real morrisanimal breakdown, in Metabase's own descending-count order. */
    private Map<String, Long> morrisanimalCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("NOT_PROCESSED", 3180L);
        counts.put("CONFLICT", 2071L);
        counts.put("PROCESSED", 1176L);
        counts.put("PROCESSED_WITH_SOME_CONFLICTS", 381L);
        counts.put("IN_PROGRESS", 164L);
        counts.put("CANCEL", 8L);
        counts.put("SUSPENDED", 6L);
        counts.put("NO_MESSAGE", 1L);
        return counts;
    }

    @Test
    void returnsNothingWhenNoDatabaseHasBeenFixedYet() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());

        assertThat(service.statusForProject(1L)).isEmpty();
        // And crucially does not call Metabase at all -- a brand new project must not make a network
        // request on every page load.
        verify(metabaseClient, never()).fetchDatabases();
    }

    @Test
    void mapsEachProductTypeToItsOwnCollection() {
        configured(ProductType.MESSAGE, "morrisanimal");
        configured(ProductType.CONTENT, "artnet");
        configured(ProductType.EMAIL, "bakktemail");
        when(metabaseClient.fetchDatabases())
                .thenReturn(List.of(db(195, "morrisanimal"), db(45, "artnet"), db(176, "bakktemail")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 1L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of("it@customer.com", 1L));

        List<MetabaseStatusDto> out = service.statusForProject(1L);

        assertThat(out).extracting(MetabaseStatusDto::getProductType)
                .containsExactly("CONTENT", "EMAIL", "MESSAGE"); // sorted, so the page never reshuffles
        assertThat(out).extracting(MetabaseStatusDto::getCollection)
                .containsExactly("MoveWorkSpaces", "emailWorkSpace", "MessageWorkSpace");
        verify(metabaseClient).countByProcessStatus(195L, "MessageWorkSpace");
        verify(metabaseClient).countByProcessStatus(45L, "MoveWorkSpaces");
        verify(metabaseClient).countByProcessStatus(176L, "emailWorkSpace");
    }

    @Test
    void fetchesTheDatabaseListOnlyOnceForAMultiTypeProject() {
        configured(ProductType.MESSAGE, "bakktmsg");
        configured(ProductType.CONTENT, "bakkt");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(1, "bakktmsg"), db(2, "bakkt")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of());
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of());

        service.statusForProject(1L);

        // 159 databases come back in that call; fetching it per product type would triple the wait.
        verify(metabaseClient, org.mockito.Mockito.times(1)).fetchDatabases();
    }

    @Test
    void ordersStatusesForReadingAndTotalsThem() {
        configured(ProductType.MESSAGE, "morrisanimal");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));
        when(metabaseClient.countByProcessStatus(195L, "MessageWorkSpace")).thenReturn(morrisanimalCounts());
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString()))
                .thenReturn(Map.of("itsupport@morrisanimalfoundation.org", 6987L));

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getStatuses()).extracting(MetabaseStatusCountDto::getStatus)
                .containsExactly("PROCESSED", "PROCESSED_WITH_SOME_CONFLICTS", "CONFLICT",
                        "IN_PROGRESS", "NOT_PROCESSED", "NO_MESSAGE", "SUSPENDED", "CANCEL");
        // The real total from that database.
        assertThat(dto.getTotalWorkspaces()).isEqualTo(6987L);
        assertThat(dto.getError()).isNull();
    }

    @Test
    void showsAStatusItHasNeverSeenRatherThanDroppingIt() {
        // The vocabulary differs per product type and can gain values; a dropped row understates
        // conflicts, so unknown statuses render after the known ones by descending count.
        configured(ProductType.EMAIL, "bakktemail");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(176, "bakktemail")));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PROCESSED", 105L);
        counts.put("SOMETHING_BRAND_NEW", 7L);
        counts.put("ANOTHER_NEW_ONE", 9L);
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(counts);
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of());

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getStatuses()).extracting(MetabaseStatusCountDto::getStatus)
                .containsExactly("PROCESSED", "ANOTHER_NEW_ONE", "SOMETHING_BRAND_NEW");
        assertThat(dto.getTotalWorkspaces()).isEqualTo(121L);
    }

    @Test
    void labelsDocumentsThatHaveNoProcessStatusAtAll() {
        configured(ProductType.MESSAGE, "morrisanimal");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PROCESSED", 3L);
        counts.put(null, 12L);
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(counts);
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of());

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getStatuses()).extracting(MetabaseStatusCountDto::getStatus)
                .contains("(no status)");
        assertThat(dto.getTotalWorkspaces()).isEqualTo(15L);
    }

    // --- failures must not look like zeros --------------------------------------------------------

    @Test
    void reportsAnUnknownDatabaseNameAsAnErrorNotAsZeroCounts() {
        configured(ProductType.MESSAGE, "renamed-since");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getError()).contains("no database called").contains("renamed-since");
        assertThat(dto.getStatuses()).isEmpty();
        assertThat(dto.getTotalWorkspaces()).isZero();
        // Never queried -- there was no database id to query.
        verify(metabaseClient, never()).countByProcessStatus(anyLong(), anyString());
    }

    @Test
    void reportsAnUnreachableMetabaseAsAnErrorOnEveryProductType() {
        configured(ProductType.MESSAGE, "bakktmsg");
        configured(ProductType.CONTENT, "bakkt");
        when(metabaseClient.fetchDatabases())
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,
                        "Could not reach Metabase at metabase.cloudfuze.com (UnknownHostException). Check METABASE_BASE_URL."));

        List<MetabaseStatusDto> out = service.statusForProject(1L);

        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(dto -> {
            // The exception type is the whole point: UnknownHostException, SSLHandshakeException and
            // ConnectException need completely different fixes, and all three are IOExceptions.
            assertThat(dto.getError()).contains("Could not reach Metabase")
                    .contains("UnknownHostException");
            assertThat(dto.getStatuses()).isEmpty();
        });
    }

    @Test
    void reportsAFailedAggregationAsAnErrorRatherThanAnEmptyBreakdown() {
        configured(ProductType.MESSAGE, "morrisanimal");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Metabase could not run the query: boom"));

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getError()).contains("could not run the query");
        assertThat(dto.getStatuses()).isEmpty();
    }

    @Test
    void matchesTheDatabaseNameCaseInsensitively() {
        // The name was typed/stored from a dropdown, but Metabase can be renamed with different casing
        // and this repo compares every identifier case-insensitively.
        configured(ProductType.MESSAGE, "MorrisAnimal");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));
        when(metabaseClient.countByProcessStatus(195L, "MessageWorkSpace")).thenReturn(Map.of("PROCESSED", 2L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of());

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getError()).isNull();
        assertThat(dto.getTotalWorkspaces()).isEqualTo(2L);
    }

    // --- the cloudfuze.com owner split ------------------------------------------------------------

    @Test
    void separatesCustomerOwnersFromCloudFuzeInternalOnes() {
        configured(ProductType.MESSAGE, "morrisanimal");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(195, "morrisanimal")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 1L));
        Map<String, Long> owners = new LinkedHashMap<>();
        owners.put("itsupport@morrisanimalfoundation.org", 6987L);
        owners.put("nagalakshmi.mangina@cloudfuze.com", 53L);
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(owners);

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getOwnerEmails()).containsExactly("itsupport@morrisanimalfoundation.org");
        // Surfaced, not hidden: on this real database those 53 rows carried 47 conflicts.
        assertThat(dto.getExcludedInternalWorkspaces()).isEqualTo(53L);
    }

    @Test
    void treatsACloudFuzeOperatedAccountOnACustomerDomainAsCustomerData() {
        // cloudfuze@azaleawang.com is CloudFuze staff working IN the customer's tenant. Matching the
        // word "cloudfuze" anywhere instead of the @cloudfuze.com domain would drop that whole
        // project's figures.
        assertThat(MetabaseStatusService.isCloudFuzeInternal("cloudfuze@azaleawang.com")).isFalse();
        assertThat(MetabaseStatusService.isCloudFuzeInternal("nagalakshmi.mangina@cloudfuze.com")).isTrue();
        assertThat(MetabaseStatusService.isCloudFuzeInternal("  MM@CloudFuze.COM  ")).isTrue();
        assertThat(MetabaseStatusService.isCloudFuzeInternal("someone@notcloudfuze.com")).isFalse();
        assertThat(MetabaseStatusService.isCloudFuzeInternal(null)).isFalse();
    }

    // ---- Drive changes (CONTENT only). The real db 214 (bluecurrent) numbers throughout: one
    // customer user, ithelp@bluecurrent.com, with 2695 of the collection's 3010 changes.

    private Map<String, Long> bluecurrentDriveChanges() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PROCESSED", 2535L);
        counts.put("NOT_PROCESSED", 81L);
        counts.put("COMPLETED", 79L);
        return counts;
    }

    @Test
    void countsDriveChangesForTheCustomersUserIdsOnly() {
        configured(ProductType.CONTENT, "bluecurrent");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(214, "bluecurrent")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 1L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of("ithelp@bluecurrent.com", 1L));
        when(metabaseClient.customerUserIds(214L))
                .thenReturn(Map.of("6a726233e727ce1468cc2141", "ithelp@bluecurrent.com"));
        when(metabaseClient.countDriveChangesByStatus(eq(214L), any())).thenReturn(bluecurrentDriveChanges());

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        assertThat(dto.getDriveChanges()).extracting(MetabaseStatusCountDto::getStatus)
                // PROCESSED then COMPLETED then NOT_PROCESSED -- STATUS_ORDER, not Metabase's
                // count order, so the page never reshuffles between reads.
                .containsExactly("PROCESSED", "COMPLETED", "NOT_PROCESSED");
        assertThat(dto.getTotalDriveChanges()).isEqualTo(2695L);
        assertThat(dto.getDriveChangeUsers()).singleElement()
                .satisfies(u -> {
                    assertThat(u.getEmail()).isEqualTo("ithelp@bluecurrent.com");
                    // The id, not just the email: it is what reproduces the figure in Metabase.
                    assertThat(u.getUserId()).isEqualTo("6a726233e727ce1468cc2141");
                });
    }

    @Test
    void doesNotReadDriveChangesForEmailOrMessage() {
        configured(ProductType.EMAIL, "bluecurrentemail");
        configured(ProductType.MESSAGE, "bluecurrentmsg");
        when(metabaseClient.fetchDatabases())
                .thenReturn(List.of(db(215, "bluecurrentemail"), db(216, "bluecurrentmsg")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 1L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of("it@customer.com", 1L));

        List<MetabaseStatusDto> out = service.statusForProject(1L);

        // DriveChangeIdDetails is a Drive concept -- the collection does not exist in these
        // databases, so asking would be a guaranteed error rather than a zero.
        assertThat(out).allSatisfy(dto -> assertThat(dto.getDriveChanges()).isNull());
        verify(metabaseClient, never()).customerUserIds(anyLong());
    }

    @Test
    void reportsNoCustomerUserRatherThanCountingZeroChanges() {
        configured(ProductType.CONTENT, "bluecurrent");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(214, "bluecurrent")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 1L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of("it@customer.com", 1L));
        // Every user in this database is CloudFuze staff.
        when(metabaseClient.customerUserIds(214L)).thenReturn(Map.of());

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        // Filtering on an empty id list would match nothing and render as "0 Drive changes", which
        // reads as "nothing migrated". The real answer is that nobody can be attributed.
        assertThat(dto.getDriveChangesError()).contains("no non-CloudFuze user");
        assertThat(dto.getDriveChanges()).isNull();
        verify(metabaseClient, never()).countDriveChangesByStatus(anyLong(), any());
    }

    @Test
    void aDriveChangeFailureLeavesTheWorkspaceCountsOnScreen() {
        configured(ProductType.CONTENT, "bluecurrent");
        when(metabaseClient.fetchDatabases()).thenReturn(List.of(db(214, "bluecurrent")));
        when(metabaseClient.countByProcessStatus(anyLong(), anyString())).thenReturn(Map.of("PROCESSED", 42L));
        when(metabaseClient.countByOwnerEmail(anyLong(), anyString())).thenReturn(Map.of("ithelp@bluecurrent.com", 42L));
        when(metabaseClient.customerUserIds(214L))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Metabase rejected our API key."));

        MetabaseStatusDto dto = service.statusForProject(1L).get(0);

        // Two independent reads: one failing is no reason to blank the other.
        assertThat(dto.getDriveChangesError()).isEqualTo("Metabase rejected our API key.");
        assertThat(dto.getError()).isNull();
        assertThat(dto.getTotalWorkspaces()).isEqualTo(42L);
    }
}
