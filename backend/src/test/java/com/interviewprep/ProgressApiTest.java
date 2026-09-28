package com.interviewprep;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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

/**
 * The progress page and the home page's coverage: each unit's stage, what is left out, and the
 * project questions. Everything in the fixtures is invented; this repository is public.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProgressApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private static final String PROJECTS = """
      { "version": 1,
        "projects": [
          { "key": "ledger", "name": "Example ledger", "summary": "Built the posting service.",
            "questions": [
              { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through the ledger." },
              { "key": "ledger-why", "rung": "why", "prompt": "Why double entry?" } ] } ] }
      """;

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;
  private final LocalDate today = LocalDate.now(INDIA);

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values"
        + " ('dsa', 'DSA', 60, 0), ('databases', 'Databases', 40, 1)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values"
        + " ('dsa.window', 'dsa', null, 'Sliding window', 0), ('db.sql', 'databases', null, 'SQL', 0),"
        + " ('db.sql.cte', 'databases', 'db.sql', 'Common table expressions', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha,"
        + " units_added) values ('2026.39.1', 'sha256:t', 't', 6) returning id", Long.class);
    unit("dsa.window.concept", "dsa.window", "shared", "draft", release);
    unit("dsa.window.p1", "dsa.window", "shared", "draft", release);
    unit("dsa.window.old", "dsa.window", "shared", "retired", release);
    unit("db.sql.joins", "db.sql", "shared", "draft", release);
    unit("db.sql.tester-only", "db.sql", "learner:tester", "draft", release);
    unit("db.sql.other-only", "db.sql", "learner:other", "draft", release);
    unit("db.sql.cte.recursive", "db.sql.cte", "shared", "draft", release);
  }

  private void unit(String id, String topic, String visibility, String state, long release) {
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin,"
        + " visibility, state, version, body, content_hash, release_id) values (?, ?, 'concept', ?, 2, 20, '{}',"
        + " 'synthesized', ?, ?, 1, '{}'::jsonb, 'h', ?)",
        id, topic, "Title of " + id, visibility, state, release);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String login, String body) throws Exception {
    return mvc.perform(as(request, login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  /** A rating given at noon, India time, {@code daysAgo} days ago. */
  private void attempt(String slug, String unitId, String rating, int daysAgo) {
    jdbc.update("insert into attempt (learner_id, unit_id, rating, created_at)"
        + " select id, ?, ?, ? from learner where slug = ?",
        unitId, rating, today.minusDays(daysAgo).atTime(12, 0).atZone(INDIA).toOffsetDateTime(), slug);
  }

  private String unitPath(String id, String field) {
    return "$.units[?(@.unitId == '" + id + "')]." + field;
  }

  /** Four "good" reviews ending three days ago: a 16-day gap, so solid until it comes round. */
  private void makeProgress() throws Exception {
    mvc.perform(as(get("/api/me"), TESTER)).andExpect(status().isOk());  // registers the learner
    attempt("tester", "dsa.window.concept", "good", 10);
    attempt("tester", "dsa.window.concept", "good", 9);
    attempt("tester", "dsa.window.concept", "good", 6);
    attempt("tester", "dsa.window.concept", "good", 3);
    attempt("tester", "dsa.window.p1", "good", 1);  // one day's gap, due today
    attempt("tester", "dsa.window.old", "good", 1);  // retired: left out everywhere
    send(post("/api/units/db.sql.joins/attempts"), TESTER, "{\"rating\": \"good\"}").andExpect(status().isOk());
  }

  @Test
  void eachUnitHasItsStageReviewCountAndNextDueDate() throws Exception {
    makeProgress();
    mvc.perform(as(get("/api/progress"), TESTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.today").value(today.toString()))
        .andExpect(jsonPath(unitPath("dsa.window.concept", "stage")).value("solid"))
        .andExpect(jsonPath(unitPath("dsa.window.concept", "reviews")).value(3))
        .andExpect(jsonPath(unitPath("dsa.window.concept", "dueOn")).value(today.plusDays(13).toString()))
        .andExpect(jsonPath(unitPath("dsa.window.p1", "stage")).value("review-due"))
        .andExpect(jsonPath(unitPath("dsa.window.p1", "dueOn")).value(today.toString()))
        .andExpect(jsonPath(unitPath("db.sql.joins", "stage")).value("learned"))
        .andExpect(jsonPath(unitPath("db.sql.joins", "reviews")).value(0))
        .andExpect(jsonPath(unitPath("db.sql.tester-only", "stage")).value("not-started"))
        // In curriculum order, by area, with the areas' counts.
        .andExpect(jsonPath("$.areas[0].domainId").value("dsa"))
        .andExpect(jsonPath("$.areas[0].stages.solid").value(1))
        .andExpect(jsonPath("$.areas[0].stages.reviewDue").value(1))
        .andExpect(jsonPath("$.areas[1].domainId").value("databases"))
        .andExpect(jsonPath("$.areas[1].stages.learned").value(1))
        .andExpect(jsonPath("$.areas[1].stages.notStarted").value(2));
  }

  @Test
  void retiredUnitsAndTheOtherLearnersPrivateUnitsAreLeftOutAndNothingLeaksAcross() throws Exception {
    makeProgress();
    mvc.perform(as(get("/api/progress"), TESTER))
        .andExpect(jsonPath("$.units[*].unitId", containsInAnyOrder("dsa.window.concept", "dsa.window.p1",
            "db.sql.joins", "db.sql.tester-only", "db.sql.cte.recursive")));
    // The other learner sees their own private unit, none of the tester's, and none of their attempts.
    mvc.perform(as(get("/api/progress"), OTHER))
        .andExpect(jsonPath("$.units[*].unitId", containsInAnyOrder("dsa.window.concept", "dsa.window.p1",
            "db.sql.joins", "db.sql.other-only", "db.sql.cte.recursive")))
        .andExpect(jsonPath("$.units[?(@.stage != 'not-started')]").isEmpty())
        .andExpect(jsonPath("$.projects").isEmpty());
  }

  @Test
  void notForMeUnitsAreFlaggedAndLeftOutOfTheCountsHereAndOnTheHomePage() throws Exception {
    makeProgress();
    // The topic's parent is "SQL"; marking the subtopic keeps its one unit out.
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"db.sql.cte\", \"excluded\": true}")
        .andExpect(status().isOk());
    mvc.perform(as(get("/api/progress"), TESTER))
        .andExpect(jsonPath("$.notForMe").value(1))
        .andExpect(jsonPath(unitPath("db.sql.cte.recursive", "notForMe")).value(true))
        .andExpect(jsonPath(unitPath("db.sql.joins", "notForMe")).value(false))
        .andExpect(jsonPath("$.areas[1].stages.notStarted").value(1));
    mvc.perform(as(get("/api/dashboard"), TESTER))
        .andExpect(jsonPath("$.coverage[1].domainId").value("databases"))
        .andExpect(jsonPath("$.coverage[1].done").value(1))
        .andExpect(jsonPath("$.coverage[1].total").value(2))
        .andExpect(jsonPath("$.coverage[1].stages.learned").value(1))
        .andExpect(jsonPath("$.coverage[0].stages.solid").value(1))
        .andExpect(jsonPath("$.coverage[0].stages.reviewDue").value(1))
        .andExpect(jsonPath("$.coverage[0].done").value(2))
        .andExpect(jsonPath("$.coverage[0].total").value(2));
    // Marking the whole parent topic takes the tester's private unit and the joins out too.
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"db.sql\", \"excluded\": true}");
    mvc.perform(as(get("/api/progress"), TESTER))
        .andExpect(jsonPath("$.notForMe").value(3))
        .andExpect(jsonPath("$.areas.length()").value(1));
  }

  @Test
  void projectQuestionsHaveTheSameStagesAndStayWithTheirOwner() throws Exception {
    send(post("/api/projects/import"), TESTER, PROJECTS).andExpect(status().isOk());
    long walk = jdbc.queryForObject("select id from project_question where key = 'ledger-walk'", Long.class);
    long project = jdbc.queryForObject("select id from experience_project where key = 'ledger'", Long.class);
    send(post("/api/projects/questions/" + walk + "/attempts"), TESTER, "{\"rating\": \"easy\"}")
        .andExpect(status().isOk());
    mvc.perform(as(get("/api/progress"), TESTER))
        .andExpect(jsonPath("$.projects[0].projectId").value(project))
        .andExpect(jsonPath("$.projects[0].name").value("Example ledger"))
        .andExpect(jsonPath("$.projects[0].stages.learned").value(1))
        .andExpect(jsonPath("$.projects[0].stages.notStarted").value(1))
        .andExpect(jsonPath("$.projects[0].questions[0].questionId").value(walk))
        .andExpect(jsonPath("$.projects[0].questions[0].stage").value("learned"))
        .andExpect(jsonPath("$.projects[0].questions[0].dueOn").value(today.plusDays(3).toString()))
        .andExpect(jsonPath("$.projects[0].questions[1].prompt").value("Why double entry?"))
        .andExpect(jsonPath("$.projects[0].questions[1].stage").value("not-started"));
    mvc.perform(as(get("/api/progress"), OTHER)).andExpect(jsonPath("$.projects").isEmpty());
  }

  @Test
  void progressNeedsASignedInLearner() throws Exception {
    mvc.perform(get("/api/progress")).andExpect(status().is4xxClientError());
  }
}
