package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.WorkspacePairImportResultDto;
import com.cloudfuze.deltatracker.entity.Server;
import com.cloudfuze.deltatracker.entity.WorkspacePair;
import com.cloudfuze.deltatracker.exception.ApiException;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the CSV import row-failure handling (Part 3). Proves an oversized cell is reported
 * as a "Row N: <field> exceeds maximum length" entry in errors[] and skipped, while every other row
 * in the same file still imports -- instead of a DataIntegrityViolationException aborting the whole
 * import at the first oversized value.
 *
 * <p>A pair's duplicate identity includes its combination now (see
 * WorkspaceCombinationService.getOrCreate, called from processRow), so the repository lookup takes
 * combination as a 5th key column -- {@code any()} (not {@code anyString()}) is used where a row has
 * no combination value at all, since Mockito's {@code anyString()} doesn't match a null argument.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspacePairServiceTest {

    private static final Long SID = 1L;

    @Mock private WorkspacePairRepository workspacePairRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private WorkspaceCombinationService workspaceCombinationService;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkspaceCombinationRepository workspaceCombinationRepository;
    @Mock private ServerPurgeService serverPurgeService;
    @Mock private ServerService serverService;
    @Mock private TeamService teamService;

    private WorkspacePairService service;
    private Server server;

    @BeforeEach
    void setUp() {
        service = new WorkspacePairService(workspacePairRepository, serverRepository, workspaceCombinationService,
                projectRepository, workspaceCombinationRepository, serverPurgeService, serverService, teamService);
        server = new Server("SRV-1");
        server.setId(SID);

        when(serverRepository.findById(SID)).thenReturn(Optional.of(server));
        when(workspacePairRepository
                .findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPathAndCombination(
                        anyLong(), anyString(), anyString(), anyString(), anyString(), any(String.class)))
                .thenReturn(Optional.empty());
        when(workspacePairRepository.save(any(WorkspacePair.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workspacePairRepository.countByServerId(SID)).thenReturn(1L);
        when(serverRepository.save(any(Server.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void oversizedCellIsRowErrorAndRestOfFileStillImports() {
        String hugePath = "/" + "x".repeat(10_000);
        String csv = "source_email,source_path,destination_email,destination_path\n"
                + "good@x.com,/ok,dst@x.com,/dst\n"
                + "bad@x.com," + hugePath + ",dst2@x.com,/dst\n";
        MockMultipartFile file = new MockMultipartFile("file", "pairs.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        WorkspacePairImportResultDto result = service.importCsv(SID, file);

        // The good row imported; the oversized row was reported and skipped, not thrown.
        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0))
                .isEqualTo("Row 3: source_path exceeds maximum length");
    }

    /**
     * A row that matches an existing pair on every column (source/destination email + path AND the
     * same combination) is reported as a duplicate and skipped, while a genuinely new row in the same
     * file still imports. This is what makes a re-uploaded file say "already imported" instead of
     * silently re-updating rows.
     */
    @Test
    void identicalRowIsReportedAsDuplicateAndRestStillImports() {
        // Row 2 already exists byte-for-byte (same combination); row 3 is new.
        WorkspacePair existing = new WorkspacePair(server, "dup@x.com", "dst@x.com");
        existing.setSourcePath("/ok");
        existing.setDestinationPath("/dst");
        existing.setCombination("Gmail->Outlook");
        when(workspacePairRepository
                .findByServerIdAndSourceEmailAndSourcePathAndDestinationEmailAndDestinationPathAndCombination(
                        SID, "dup@x.com", "/ok", "dst@x.com", "/dst", "Gmail->Outlook"))
                .thenReturn(Optional.of(existing));

        String csv = "source_email,source_path,destination_email,destination_path,combination\n"
                + "dup@x.com,/ok,dst@x.com,/dst,Gmail->Outlook\n"
                + "new@x.com,/n,dst2@x.com,/dst2,Gmail->Outlook\n";
        MockMultipartFile file = new MockMultipartFile("file", "pairs.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        WorkspacePairImportResultDto result = service.importCsv(SID, file);

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isZero();
        assertThat(result.getDuplicateCount()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getDuplicates()).hasSize(1);
        assertThat(result.getDuplicates().get(0))
                .isEqualTo("Row 2: dup@x.com → dst@x.com already exists (skipped)");
    }

    // ---- Re-upload replaces a combination's pairs -----------------------------------------------

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "pairs.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private static WorkspacePair pair(String source) {
        WorkspacePair p = new WorkspacePair();
        p.setSourceEmail(source);
        p.setDestinationEmail("dest@cloudfuze.com");
        p.setCombination("MyDrive to OneDrive");
        return p;
    }

    /**
     * A re-upload is a REPLACE, not a merge: the file is the new complete list for that combination,
     * so rows the uploader dropped from it must disappear rather than linger with nothing on screen
     * to explain them.
     */
    @Test
    void reUploadingDeletesTheCombinationsPreviousPairs() {
        when(workspacePairRepository.findByServerIdAndCombinationIgnoreCase(SID, "MyDrive to OneDrive"))
                .thenReturn(java.util.List.of(pair("old1@cloudfuze.com"), pair("old2@cloudfuze.com")));

        WorkspacePairImportResultDto result = service.importCsvForServerCombination(SID, "MyDrive to OneDrive",
                csv("source_email,destination_email\nnew@cloudfuze.com,dest@cloudfuze.com\n"));

        assertThat(result.getReplacedCount()).isEqualTo(2);
        assertThat(result.getCreatedCount()).isEqualTo(1);
        verify(workspacePairRepository).deleteAll(argThat(rows -> {
            java.util.List<WorkspacePair> list = new java.util.ArrayList<>();
            rows.forEach(list::add);
            return list.size() == 2;
        }));
    }

    /**
     * The safety valve. Rows are deleted before the new ones are read, so a file that yields nothing
     * usable would otherwise leave the combination empty -- worse than refusing the upload.
     */
    @Test
    void aReUploadWithNoUsableRowsIsRefusedSoTheOldPairsSurvive() {
        when(workspacePairRepository.findByServerIdAndCombinationIgnoreCase(SID, "MyDrive to OneDrive"))
                .thenReturn(java.util.List.of(pair("old1@cloudfuze.com")));

        // Header only -- valid columns, no data.
        assertThatThrownBy(() -> service.importCsvForServerCombination(SID, "MyDrive to OneDrive",
                csv("source_email,destination_email\n")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("were kept");
    }

    @Test
    void aFirstUploadWithNothingToReplaceStillReportsZero() {
        when(workspacePairRepository.findByServerIdAndCombinationIgnoreCase(SID, "MyDrive to OneDrive"))
                .thenReturn(java.util.List.of());

        WorkspacePairImportResultDto result = service.importCsvForServerCombination(SID, "MyDrive to OneDrive",
                csv("source_email,destination_email\nnew@cloudfuze.com,dest@cloudfuze.com\n"));

        assertThat(result.getReplacedCount()).isZero();
        assertThat(result.getCreatedCount()).isEqualTo(1);
    }
}
