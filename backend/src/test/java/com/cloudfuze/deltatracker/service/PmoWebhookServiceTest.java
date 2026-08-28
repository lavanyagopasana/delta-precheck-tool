package com.cloudfuze.deltatracker.service;

import com.cloudfuze.deltatracker.dto.PmoDeltaPhaseWebhookRequest;
import com.cloudfuze.deltatracker.dto.PmoProjectDto;
import com.cloudfuze.deltatracker.dto.PmoSyncResultDto;
import com.cloudfuze.deltatracker.dto.PmoWebhookProjectDto;
import com.cloudfuze.deltatracker.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PmoWebhookService} -- the piece that decides whether an inbound call really
 * is PMO (verifyApiKey) and translates its payload into what {@link PmoSyncService#ingestOne} expects.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PmoWebhookServiceTest {

    @Mock
    private PmoSyncService pmoSyncService;

    private PmoWebhookService service;

    @BeforeEach
    void setUp() {
        service = new PmoWebhookService(pmoSyncService);
        ReflectionTestUtils.setField(service, "expectedApiKey", "the-real-key");
    }

    private static PmoDeltaPhaseWebhookRequest request(String event, PmoWebhookProjectDto project) {
        PmoDeltaPhaseWebhookRequest r = new PmoDeltaPhaseWebhookRequest();
        r.setEvent(event);
        r.setProject(project);
        return r;
    }

    private static PmoWebhookProjectDto project() {
        PmoWebhookProjectDto p = new PmoWebhookProjectDto();
        p.setId("ext-1");
        p.setName("acme");
        p.setCustomerName("Acme Inc");
        p.setProjectManager("Harika");
        p.setStatus("ACTIVE");
        p.setPhase("DELTA");
        p.setMigrationTypes("Gmail - Gmail");
        return p;
    }

    @Test
    void theCorrectKeyIsAccepted() {
        service.verifyApiKey("the-real-key");
        // No exception -- that IS the assertion.
    }

    @Test
    void aWrongKeyIsRejected() {
        assertThatThrownBy(() -> service.verifyApiKey("someone-elses-key"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMissingKeyIsRejected() {
        assertThatThrownBy(() -> service.verifyApiKey(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnconfiguredWebhookRefusesEverythingRatherThanAdmittingEveryone() {
        ReflectionTestUtils.setField(service, "expectedApiKey", "");

        assertThatThrownBy(() -> service.verifyApiKey("anything"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aDeltaPhaseEventIsTranslatedAndForwardedToIngestOne() {
        when(pmoSyncService.ingestOne(any(PmoProjectDto.class))).thenReturn(new PmoSyncResultDto());

        service.handle(request("PROJECT_PHASE_MOVED_TO_DELTA", project()));

        ArgumentCaptor<PmoProjectDto> captor = ArgumentCaptor.forClass(PmoProjectDto.class);
        verify(pmoSyncService).ingestOne(captor.capture());
        PmoProjectDto dto = captor.getValue();
        assertThat(dto.getExternalId()).isEqualTo("ext-1");
        assertThat(dto.getName()).isEqualTo("acme");
        assertThat(dto.getCustomerName()).isEqualTo("Acme Inc");
        assertThat(dto.getManagerName()).isEqualTo("Harika");
        assertThat(dto.getStatus()).isEqualTo("ACTIVE");
        assertThat(dto.getPhase()).isEqualTo("DELTA");
        assertThat(dto.getMigrationTypes()).isEqualTo("Gmail - Gmail");
    }

    @Test
    void anUnrecognizedEventIsIgnoredRatherThanRejected() {
        service.handle(request("SOMETHING_ELSE_ENTIRELY", project()));

        verify(pmoSyncService, never()).ingestOne(any());
    }

    @Test
    void aMissingProjectOnADeltaEventIsRejected() {
        assertThatThrownBy(() -> service.handle(request("PROJECT_PHASE_MOVED_TO_DELTA", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(pmoSyncService, never()).ingestOne(any());
    }

    @Test
    void anIngestionFailureSurfacesAsAnErrorRatherThanASilent200() {
        PmoSyncResultDto failed = new PmoSyncResultDto();
        failed.addError("boom");
        when(pmoSyncService.ingestOne(any(PmoProjectDto.class))).thenReturn(failed);

        assertThatThrownBy(() -> service.handle(request("PROJECT_PHASE_MOVED_TO_DELTA", project())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
