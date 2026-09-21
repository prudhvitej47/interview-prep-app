package com.interviewprep;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The walking skeleton: browser -> Spring MVC -> JDBC -> Flyway-migrated Postgres and back.
 *
 * <p>MockMvc is built from the context by hand because Spring Boot 4 dropped
 * {@code @AutoConfigureMockMvc} from spring-boot-test-autoconfigure. Two lines here beat adding a
 * dependency for an annotation.
 */
@SpringBootTest
@ActiveProfiles("test")
class TopicApiTest extends PostgresTestBase {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // Through the real security chain, so these tests see what a browser would.
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void topicsEndpointAnswersWithJson() throws Exception {
    mockMvc
        .perform(get("/api/topics").header("Tailscale-User-Login", "tester@example.com"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void healthIsUp() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void unknownAppPathFallsBackToTheSinglePageApp() throws Exception {
    // The React router owns /plan; the server must not 404 it.
    mockMvc.perform(get("/plan")).andExpect(status().isOk());
  }

  @Test
  void aDottedUnitRouteStillReachesTheSinglePageApp() throws Exception {
    // Unit ids are dotted, so any "a dot means it is a file" rule would 404 the most
    // important route in the app.
    mockMvc
        .perform(get("/units/ds.transactions.idempotency-keys"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
  }

  @Test
  void aMissingAssetIsNotFound() throws Exception {
    // Returning index.html here would hand the browser HTML where it asked for JavaScript,
    // turning a broken build into a blank page with a confusing console error.
    mockMvc.perform(get("/assets/does-not-exist.js")).andExpect(status().isNotFound());
  }

  @Test
  void anUnknownApiPathIsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/nope").header("Tailscale-User-Login", "tester@example.com"))
        .andExpect(status().isNotFound());
  }
}
