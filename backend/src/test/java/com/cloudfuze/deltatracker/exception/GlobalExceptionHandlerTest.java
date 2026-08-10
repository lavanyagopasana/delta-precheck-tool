package com.cloudfuze.deltatracker.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression cover for a 404 being reported as a 500.
 *
 * <p>{@code @ExceptionHandler(Exception.class)} is declared on a class that does not extend
 * {@code ResponseEntityExceptionHandler}, so it outranks nothing and catches everything -- including
 * Spring's own {@code NoResourceFoundException}/{@code NoHandlerFoundException}. Before
 * {@code handleSpringStatusException} was added, every request to a path with no handler came back
 * as {@code 500 "Something went wrong. Please try again."}
 *
 * <p>That cost real debugging time: the frontend's Delta History panel called
 * {@code /api/combinations/{id}/delta-cycles} against a backend process that had been started before
 * the endpoint was compiled, and the resulting "500 something went wrong" pointed the investigation
 * at the service and database layers rather than at the missing route it actually was.
 *
 * <p>Uses the same {@code @SpringBootTest} + H2 + auth-off harness as EndpointCharacterizationTest,
 * because the point is what the dispatcher does with an unrouted request -- calling the advice
 * directly would assert the fix restates itself rather than that routing produces a 404 end to end.
 *
 * <p>{@code @TestPropertySource} is required, not just {@code @ActiveProfiles("test")} -- Spring
 * Boot's config-data precedence ranks a {@code file:./application.properties} in the working
 * directory a developer launches tests from ABOVE a profile-specific classpath file, so a local-dev
 * override that happens to set {@code spring.datasource.url} (or {@code azure.client-id}, etc.)
 * would otherwise silently win over this profile's H2/no-auth setup with no visible warning.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:/application-test.properties")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unmappedApiRouteReturns404NotServerError() throws Exception {
        mockMvc.perform(get("/api/combinations/1/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("That resource doesn't exist."));
    }

    @Test
    void missingStaticUploadReturns404() throws Exception {
        mockMvc.perform(get("/uploads/does-not-exist.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // NoResourceFoundException.getBody().getDetail() names the resolved resource path. The handler
    // substitutes a fixed phrase precisely so that never reaches the client -- assert it, otherwise a
    // later "improve the message" edit could reintroduce the leak unnoticed.
    @Test
    void notFoundMessageDoesNotLeakTheResolvedPath() throws Exception {
        mockMvc.perform(get("/uploads/secret-evidence-file.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", not(containsString("secret-evidence-file"))));
    }
}
