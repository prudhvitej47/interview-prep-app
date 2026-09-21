package com.interviewprep.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.interviewprep.PostgresTestBase;
import com.interviewprep.RealCsrf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who gets in, and what onboarding stores. Runs through the real Spring Security chain.
 *
 * <p>What this cannot test is that {@code tailscale serve} strips a forged identity header; that is
 * a property of the proxy, and was verified on the VM by capturing the traffic that reached the app.
 */
@SpringBootTest
@ActiveProfiles("test")
class IdentityAndOnboardingTest extends PostgresTestBase {

  private static final String HEADER = "Tailscale-User-Login";
  private static final String ALL_RATED = """
      {"ratings": {"dsa": 2, "databases": 4, "distributed": 3}}""";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values"
        + " ('dsa', 'DSA and coding', 40, 0), ('databases', 'Databases and SQL', 30, 1),"
        + " ('distributed', 'Distributed systems', 30, 2)");
    jdbc.update("insert into topic (id, domain_id, name, sort_order) values"
        + " ('dsa.graphs', 'dsa', 'Graphs', 0), ('dsa.trees', 'dsa', 'Trees', 1),"
        + " ('db.indexes', 'databases', 'Indexes', 0), ('ds.consensus', 'distributed', 'Consensus', 0)");
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header(HEADER, login);
  }

  @Nested
  class WhoGetsIn {

    @Test
    void noIdentityIsRefused() throws Exception {
      mvc.perform(get("/api/me")).andExpect(status().isForbidden());
    }

    @Test
    void anIdentityNotOnTheListIsRefused() throws Exception {
      // Being on the tailnet is necessary, not sufficient.
      mvc.perform(as(get("/api/me"), "stranger@example.com")).andExpect(status().isForbidden());
    }

    @Test
    void anAllowedIdentityIsTheirLearner() throws Exception {
      mvc.perform(as(get("/api/me"), "tester@example.com"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.slug").value("tester"))
          .andExpect(jsonPath("$.displayName").value("Tester"));
    }

    @Test
    void aChangeWithAForgedCsrfTokenIsRefused() throws Exception {
      mvc.perform(as(put("/api/me/ratings/domains"), "tester@example.com")
              .header("X-XSRF-TOKEN", "forged").cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "different"))
              .contentType(MediaType.APPLICATION_JSON).content(ALL_RATED))
          .andExpect(status().isForbidden());
    }

    @Test
    void theLoginIsComparedTheWayEmailIs() throws Exception {
      mvc.perform(as(get("/api/me"), "Tester@Example.COM"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.slug").value("tester"));
    }

    @Test
    void theLearnerIsCreatedOnceNotOnEveryRequest() throws Exception {
      mvc.perform(as(get("/api/me"), "tester@example.com")).andExpect(status().isOk());
      mvc.perform(as(get("/api/me"), "tester@example.com")).andExpect(status().isOk());
      assertThat(jdbc.queryForObject("select count(*) from learner where slug = 'tester'",
          Integer.class)).isEqualTo(1);
    }

    @Test
    void theAppShellAndHealthNeedNoIdentity() throws Exception {
      mvc.perform(get("/")).andExpect(status().isOk());
      mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void readingHandsTheBrowserACsrfToken() throws Exception {
      mvc.perform(as(get("/api/me"), "tester@example.com"))
          .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void aChangeWithoutTheCsrfTokenIsRefusedEvenWithAValidIdentity() throws Exception {
      // The case CSRF protection exists for here: serve adds the identity to any request from the
      // learner's browser, including one a malicious page sends. Without the token, it is refused.
      mvc.perform(as(put("/api/me/ratings/domains"), "tester@example.com")
              .contentType(MediaType.APPLICATION_JSON).content(ALL_RATED))
          .andExpect(status().isForbidden());
      assertThat(jdbc.queryForObject("select count(*) from learner_competency", Integer.class)).isZero();
    }
  }

  @Nested
  class Onboarding {

    private org.springframework.test.web.servlet.ResultActions rate(String login, String body)
        throws Exception {
      return mvc.perform(as(put("/api/me/ratings/domains"), login).with(RealCsrf.token(mvc, login))
          .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void domainsArriveInCurriculumOrderWithExamples() throws Exception {
      mvc.perform(as(get("/api/domains"), "tester@example.com"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value("dsa"))
          .andExpect(jsonPath("$[0].examples[0]").value("Graphs"))
          .andExpect(jsonPath("$[2].id").value("distributed"));
    }

    @Test
    void aNewLearnerIsNotOnboarded() throws Exception {
      mvc.perform(as(get("/api/me"), "tester@example.com"))
          .andExpect(jsonPath("$.onboarded").value(false));
    }

    @Test
    void ratingEveryDomainCompletesOnboarding() throws Exception {
      rate("tester@example.com", ALL_RATED)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.onboarded").value(true))
          .andExpect(jsonPath("$.domainRatings.databases").value(4));
      assertThat(jdbc.queryForObject(
          "select count(*) from learner_competency where scope = 'domain' and source = 'self-rating'",
          Integer.class)).isEqualTo(3);
    }

    @Test
    void aMissingDomainIsRejected() throws Exception {
      rate("tester@example.com", """
          {"ratings": {"dsa": 2, "databases": 4}}""").andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownDomainIsRejected() throws Exception {
      rate("tester@example.com", """
          {"ratings": {"dsa": 2, "databases": 4, "distributed": 3, "astrology": 5}}""")
          .andExpect(status().isBadRequest());
    }

    @Test
    void aRatingOutsideZeroToFiveIsRejected() throws Exception {
      rate("tester@example.com", """
          {"ratings": {"dsa": 6, "databases": 4, "distributed": 3}}""")
          .andExpect(status().isBadRequest());
      assertThat(jdbc.queryForObject("select count(*) from learner_competency", Integer.class)).isZero();
    }

    @Test
    void ratingAgainReplacesTheEarlierAnswer() throws Exception {
      rate("tester@example.com", ALL_RATED).andExpect(status().isOk());
      rate("tester@example.com", """
          {"ratings": {"dsa": 5, "databases": 4, "distributed": 3}}""")
          .andExpect(jsonPath("$.domainRatings.dsa").value(5));
      assertThat(jdbc.queryForObject("select count(*) from learner_competency", Integer.class))
          .isEqualTo(3);
    }

    @Test
    void oneLearnersRatingsAreTheirsAlone() throws Exception {
      rate("tester@example.com", ALL_RATED).andExpect(status().isOk());
      mvc.perform(as(get("/api/me"), "other@example.com"))
          .andExpect(jsonPath("$.slug").value("other"))
          .andExpect(jsonPath("$.onboarded").value(false))
          .andExpect(jsonPath("$.domainRatings").isEmpty());
    }

    @Test
    void withNoCurriculumLoadedThereIsNothingToRate() throws Exception {
      jdbc.execute("truncate domain cascade");
      rate("tester@example.com", "{\"ratings\": {}}").andExpect(status().isConflict());
      mvc.perform(as(get("/api/me"), "tester@example.com"))
          .andExpect(jsonPath("$.onboarded").value(false));
    }
  }
}
