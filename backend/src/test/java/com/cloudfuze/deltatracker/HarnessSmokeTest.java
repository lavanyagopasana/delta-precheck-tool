package com.cloudfuze.deltatracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Validates the test harness: the Spring context boots on H2 with auth off, and an endpoint is
// reachable without a token. If this is green, the characterization snapshots can build on it.
//
// @TestPropertySource wins over a file:./application.properties in the working directory a
// developer launches tests from, which @ActiveProfiles("test") alone does not -- see the same
// comment on EndpointCharacterizationTest for the full explanation.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:/application-test.properties")
class HarnessSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardSummaryIsReachable() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary")).andExpect(status().isOk());
    }
}
