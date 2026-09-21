package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The week plan through the real security chain, on a small curriculum. */
@SpringBootTest
@ActiveProfiles("test")
class PlanApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  // Every day and plenty of time, so every unit fits whichever weekday the test runs on: a week
  // opened mid-week is planned over the days left (WeekPlannerTest covers that rule).
  private static final String SETTINGS =
      "{\"hoursPerWeek\": 40, \"studyDays\": [1, 2, 3, 4, 5, 6, 7], \"weights\": {}}";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;
  private final LocalDate monday =
      LocalDate.now(ZoneId.of("Asia/Kolkata")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values"
        + " ('dsa', 'DSA', 60, 0), ('databases', 'Databases', 40, 1)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values"
        + " ('dsa.window', 'dsa', null, 'Sliding window', 0), ('db.sql', 'databases', null, 'SQL', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.39.1', 'sha256:t', 't') returning id", Long.class);
    unit("dsa.window.concept", "dsa.window", "concept", 30, "shared", release);
    unit("dsa.window.p1", "dsa.window", "coding", 25, "shared", release);
    unit("db.sql.joins", "db.sql", "sql", 20, "shared", release);
    unit("db.sql.tester-only", "db.sql", "project", 30, "learner:tester", release);
    jdbc.update("insert into unit_prereq (unit_id, prereq_id) values ('dsa.window.p1', 'dsa.window.concept')");
  }

  private void unit(String id, String topic, String type, int minutes, String visibility, long release) {
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin,"
        + " visibility, state, version, body, content_hash, release_id) values (?, ?, ?, ?, 2, ?, '{}',"
        + " 'synthesized', ?, 'draft', 1, '{}'::jsonb, 'h', ?)",
        id, topic, type, "Title of " + id, minutes, visibility, release);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String login, String body) throws Exception {
    return mvc.perform(as(request, login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private int plans() {
    return jdbc.queryForObject("select count(*) from week_plan", Integer.class);
  }

  @Test
  void thereIsNoPlanUntilTheLearnerSaysHowMuchTimeTheyHave() throws Exception {
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weekStart").value(monday.toString()))
        .andExpect(jsonPath("$.plan").isEmpty());
    assertThat(plans()).isZero();
  }

  @Test
  void settingsAreChecked() throws Exception {
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 0, \"studyDays\": [1]}").andExpect(status().isBadRequest());
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 5, \"studyDays\": [8]}").andExpect(status().isBadRequest());
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 5, \"studyDays\": [1], \"weights\": {\"nope\": 5}}")
        .andExpect(status().isBadRequest());
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 5, \"studyDays\": [5, 1, 1], \"weights\": {\"dsa\": 80}}")
        .andExpect(jsonPath("$.studyDays.length()").value(2))
        .andExpect(jsonPath("$.studyDays[0]").value(1))
        .andExpect(jsonPath("$.weights.dsa").value(80));
  }

  @Test
  void theWeekIsMadeOnceThenKeptUntilRebuilt() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS).andExpect(status().isOk());
    int daysLeft = 8 - LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue();
    int planned = (int) Math.round(40 * 60 * 0.9 * daysLeft / 7.0);
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.plannedMinutes").value(planned))
        .andExpect(jsonPath("$.plan.goalMinutes").value((int) Math.round(planned * 0.8)))
        .andExpect(jsonPath("$.plan.items.length()").value(4))
        .andExpect(jsonPath("$.plan.items[0].reason").isNotEmpty());
    long first = jdbc.queryForObject("select id from week_plan", Long.class);

    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.items.length()").value(4));
    assertThat(jdbc.queryForObject("select id from week_plan", Long.class)).isEqualTo(first);

    mvc.perform(as(delete("/api/plan"), TESTER).with(RealCsrf.token(mvc, TESTER))).andExpect(status().isOk());
    assertThat(plans()).isZero();
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.items.length()").value(4));
    assertThat(jdbc.queryForObject("select id from week_plan", Long.class)).isNotEqualTo(first);
  }

  @Test
  void anItemIsDoneOnceTheLearnerRecordsHowItWent() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.doneMinutes").value(0));
    send(post("/api/units/db.sql.joins/attempts"), TESTER, "{\"rating\": \"good\"}").andExpect(status().isOk());
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.doneMinutes").value(20))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')].done").value(true));
  }

  @Test
  void theCardAsksAboutGuessedTopicsUntilTheyAreRated() throws Exception {
    // Onboarding rated both domains; nothing is known about the two topics themselves.
    mvc.perform(as(get("/api/me"), TESTER));
    jdbc.update("insert into learner_competency (learner_id, scope, scope_id, rating, source)"
        + " select id, 'domain', d, 3, 'self-rating' from learner, unnest(array['dsa', 'databases']) d"
        + " where slug = 'tester'");
    send(put("/api/me/week"), TESTER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.topicsToRate[?(@.topicId == 'db.sql')].currentGuess").value(3));

    send(put("/api/me/ratings/topics"), TESTER, "{\"ratings\": {\"db.sql\": 1}}").andExpect(status().isOk());
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.topicsToRate[?(@.topicId == 'db.sql')]").isEmpty())
        .andExpect(jsonPath("$.plan.topicsToRate[?(@.topicId == 'dsa.window')]").isNotEmpty());
    send(put("/api/me/ratings/topics"), TESTER, "{\"ratings\": {\"no.such\": 1}}").andExpect(status().isBadRequest());
  }

  @Test
  void eachLearnerHasTheirOwnWeekAndNeverSeesTheOthersPrivateUnits() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    send(put("/api/me/week"), OTHER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.tester-only')]").isNotEmpty());
    mvc.perform(as(get("/api/plan"), OTHER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.tester-only')]").isEmpty());
    assertThat(plans()).isEqualTo(2);
  }

  @Test
  void rebuildingNeedsTheCsrfToken() throws Exception {
    mvc.perform(as(delete("/api/plan"), TESTER)).andExpect(status().isForbidden());
  }
}
