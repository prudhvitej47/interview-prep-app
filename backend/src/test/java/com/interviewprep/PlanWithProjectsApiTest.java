package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import com.interviewprep.planner.PlanQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The week plan once a learner has imported questions about their own projects: they are planned,
 * count as done when rated, carry over, and earn stars. Everything in the fixtures is invented;
 * this repository is public.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanWithProjectsApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  // Every day and plenty of time, so the test works whichever weekday it runs on.
  private static final String SETTINGS =
      "{\"hoursPerWeek\": 40, \"studyDays\": [1, 2, 3, 4, 5, 6, 7], \"weights\": {}}";

  private static final String FILE = """
      { "version": 1,
        "projects": [
          { "key": "ledger", "name": "Example ledger", "summary": "Built the posting service.",
            "questions": [
              { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through the ledger." },
              { "key": "ledger-why", "rung": "why", "prompt": "Why double entry?" },
              { "key": "ledger-hot", "rung": "scale", "prompt": "What fails first at 10x?" } ] },
          { "key": "gateway", "name": "Example gateway", "summary": "Routed the calls.",
            "questions": [
              { "key": "gateway-why", "rung": "why", "prompt": "Why a gateway at all?" } ] } ] }
      """;

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlanQueries plans;
  private MockMvc mvc;
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
  private final LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('dsa', 'DSA', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order)"
        + " values ('dsa.window', 'dsa', null, 'Sliding window', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.39.1', 'sha256:t', 't') returning id", Long.class);
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin,"
        + " visibility, state, version, body, content_hash, release_id) values ('dsa.window.concept',"
        + " 'dsa.window', 'concept', 'Sliding window', 2, 30, '{}', 'synthesized', 'shared', 'draft', 1,"
        + " '{}'::jsonb, 'h', ?)", release);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String login, String body) throws Exception {
    return mvc.perform(as(request, login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private long question(String key) {
    return jdbc.queryForObject("select id from project_question where key = ?", Long.class, key);
  }

  private void importAndPlan(String login) throws Exception {
    send(post("/api/projects/import"), login, FILE).andExpect(status().isOk());
    send(put("/api/me/week"), login, SETTINGS).andExpect(status().isOk());
  }

  @Test
  void importedQuestionsArePlannedWithinATenthOfTheWeekOnePerDay() throws Exception {
    importAndPlan(TESTER);
    long walk = question("ledger-walk");
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(status().isOk())
        // The ledger's first rung always fits: 40 hours leave at least 30 minutes for projects.
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].kind").value("project"))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].title")
            .value("Walk me through the ledger."))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].projectName")
            .value("Example ledger"))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].rung").value("walkthrough"))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].reason",
            hasItem(startsWith("Next on the Example ledger ladder: the walk-through."))))
        .andExpect(jsonPath("$.plan.notes", hasItem(startsWith("Questions about your projects take"))));

    int planned = jdbc.queryForObject("select planned_minutes from week_plan", Integer.class);
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select unit_id, project_question_id, day, minutes from plan_item where kind = 'project'");
    assertThat(rows).isNotEmpty().allMatch(r -> r.get("unit_id") == null && r.get("project_question_id") != null);
    assertThat(rows.stream().mapToInt(r -> ((Number) r.get("minutes")).intValue()).sum()).isLessThanOrEqualTo(planned / 10);
    assertThat(rows.stream().map(r -> r.get("day")).distinct().count()).isEqualTo(rows.size());
    int daysLeft = 8 - today.getDayOfWeek().getValue();
    assertThat(rows.size()).isLessThanOrEqualTo(daysLeft);
    // The other learner has no projects, so their week has none.
    send(put("/api/me/week"), OTHER, SETTINGS);
    mvc.perform(as(get("/api/plan"), OTHER))
        .andExpect(jsonPath("$.plan.items[?(@.kind == 'project')]").isEmpty())
        .andExpect(jsonPath("$.plan.notes", not(hasItem(startsWith("Questions about your projects")))));
  }

  @Test
  void aProjectItemIsDoneOnceRatedThatWeekAndCountsForTheGoalAndStars() throws Exception {
    importAndPlan(TESTER);
    long walk = question("ledger-walk");
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.doneMinutes").value(0));
    send(post("/api/projects/questions/" + walk + "/attempts"), TESTER, "{\"rating\": \"good\"}")
        .andExpect(status().isOk());
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.doneMinutes").value(15))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + walk + ")].done").value(true));
    mvc.perform(as(get("/api/rewards"), TESTER))
        .andExpect(jsonPath("$.stars").value(1))
        .andExpect(jsonPath("$.starsThisWeek").value(1));
    // The week's result, which the goal, the streak and the next week's size are read from.
    long learner = jdbc.queryForObject("select id from learner where slug = 'tester'", Long.class);
    assertThat(plans.results(learner).getLast().done()).isEqualTo(15);
  }

  @Test
  void unratedProjectItemsCarryOverAndRatedOnesDoNot() throws Exception {
    importAndPlan(TESTER);
    long hot = question("ledger-hot");
    long why = question("gateway-why");
    long plan = jdbc.queryForObject("insert into week_plan (learner_id, week_start, planned_minutes, goal_minutes,"
        + " planner_version) select id, ?, 100, 80, 1 from learner where slug = 'tester' returning id",
        Long.class, monday.minusWeeks(1));
    jdbc.update("insert into plan_item (plan_id, project_question_id, day, kind, minutes, reason, sort_order)"
        + " values (?, ?, 1, 'project', 15, 'Next on the Example ledger ladder.', 0),"
        + " (?, ?, 2, 'project', 15, 'Next on the Example gateway ladder.', 1)", plan, hot, plan, why);
    // The gateway question was rated last week; the ledger one was not.
    jdbc.update("insert into project_attempt (learner_id, question_id, rating, created_at)"
        + " select id, ?, 'good', ? from learner where slug = 'tester'",
        why, monday.minusWeeks(1).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime());

    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + hot + ")].reason",
            hasItem(startsWith("Carried over from last week: what happens at scale on Example ledger"))))
        .andExpect(jsonPath("$.plan.items[?(@.projectQuestionId == " + why + ")].reason",
            not(hasItem(startsWith("Carried over")))));
  }

  @Test
  void aPlanItemIsAboutExactlyOneThing() {
    long learner = jdbc.queryForObject("insert into learner (slug, email, display_name) values ('x', 'x@example.com',"
        + " 'X') returning id", Long.class);
    long week = jdbc.queryForObject("insert into week_plan (learner_id, week_start, planned_minutes, goal_minutes,"
        + " planner_version) values (?, ?, 100, 80, 1) returning id", Long.class, learner, monday);
    assertThatThrownBy(() -> jdbc.update("insert into plan_item (plan_id, day, kind, minutes, reason, sort_order)"
        + " values (?, 1, 'learn', 15, 'x', 0)", week)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("insert into plan_item (plan_id, unit_id, day, kind, minutes, reason,"
        + " sort_order) values (?, 'dsa.window.concept', 1, 'project', 15, 'x', 0)", week))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
