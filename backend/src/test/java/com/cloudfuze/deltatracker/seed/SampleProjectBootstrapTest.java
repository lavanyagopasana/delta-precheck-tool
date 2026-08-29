package com.cloudfuze.deltatracker.seed;

import com.cloudfuze.deltatracker.repository.DeltaCycleRepository;
import com.cloudfuze.deltatracker.repository.DeltaCycleSignOffRepository;
import com.cloudfuze.deltatracker.repository.ProjectRepository;
import com.cloudfuze.deltatracker.repository.ServerRepository;
import com.cloudfuze.deltatracker.repository.TicketRepository;
import com.cloudfuze.deltatracker.repository.WorkspaceCombinationRepository;
import com.cloudfuze.deltatracker.repository.WorkspacePairRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SampleProjectBootstrap} -- specifically that it stays switched OFF unless somebody asks for it.
 *
 * <p>This writes invented projects, tickets and sign-off history into whatever database it is pointed
 * at. It defaulted to {@code true} and the property appeared in no properties file, no compose file
 * and no workflow, so {@code APP_SEED_SAMPLE_PROJECTS=false} could not reach the container however it
 * was configured -- demo rows landed in production on every deploy. It also uses get-or-create, so a
 * demo project deleted by hand came back on the next one.
 *
 * <p>The default is the fix, so it is asserted here directly rather than only through behaviour: a
 * future edit that flips the annotation back has to fail a test that says why it must not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SampleProjectBootstrapTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ServerRepository serverRepository;
    @Mock private WorkspaceCombinationRepository combinationRepository;
    @Mock private WorkspacePairRepository pairRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private DeltaCycleRepository deltaCycleRepository;
    @Mock private DeltaCycleSignOffRepository deltaCycleSignOffRepository;

    @InjectMocks private SampleProjectBootstrap bootstrap;

    @Test
    void theConfiguredDefaultIsOffSoNothingSeedsUnlessAsked() throws NoSuchFieldException {
        // Read straight off the annotation: the placeholder's own default is what applies when nothing
        // sets the property, which is the exact situation that put demo data in production.
        Field enabled = SampleProjectBootstrap.class.getDeclaredField("enabled");
        String expression = enabled.getAnnotation(Value.class).value();

        assertThat(expression)
                .as("sample data must be opt-in -- see this class's comment")
                .isEqualTo("${app.seed-sample-projects:false}");
    }

    @Test
    void writesNothingWhenDisabled() {
        ReflectionTestUtils.setField(bootstrap, "enabled", false);

        bootstrap.run();

        // Not "creates no projects" but "touches nothing at all": the seeding also inserts servers,
        // pairs, tickets and delta cycles, and any one of those appearing in production is the bug.
        verifyNoInteractions(projectRepository, serverRepository, combinationRepository,
                pairRepository, ticketRepository, deltaCycleRepository, deltaCycleSignOffRepository);
    }

    @Test
    void stillDoesTheWorkWhenExplicitlyEnabled() {
        // The feature is not removed, only switched off by default -- a demo or a fresh local database
        // can still ask for it. Asserted only as far as "it gets past the guard and starts seeding":
        // driving the whole seed through mocks would be asserting the mock wiring, not the behaviour,
        // and the seeding itself is exercised for real by the app-test profile.
        ReflectionTestUtils.setField(bootstrap, "enabled", true);
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        try {
            bootstrap.run();
        } catch (RuntimeException expected) {
            // Later steps need repositories this test deliberately does not stub.
        }

        verify(projectRepository).findByNameIgnoreCase("Demo prjct");
    }
}
