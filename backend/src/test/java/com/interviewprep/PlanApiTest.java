package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
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
import java.util.List;
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

  @Test
  void breaksAreTakenAheadUpToTwoAQuarterAndGiveNoPlan() throws Exception {
    // Not this week, and only Mondays.
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + monday + "\"}").andExpect(status().isBadRequest());
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + monday.plusDays(8) + "\"}").andExpect(status().isBadRequest());

    // Next week is fine.
    LocalDate next = monday.plusWeeks(1);
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + next + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.upcoming[0].taken").value(true));

    // This week, declared as a break (as if taken last week): no plan is made for it.
    jdbc.update("insert into planned_break (learner_id, week_start) select id, ? from learner where slug = 'tester'",
        monday);
    send(put("/api/me/week"), TESTER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.onBreak").value(true))
        .andExpect(jsonPath("$.plan").isEmpty());
    assertThat(plans()).isZero();

    mvc.perform(as(delete("/api/breaks/" + next), TESTER).with(RealCsrf.token(mvc, TESTER)))
        .andExpect(jsonPath("$.upcoming[0].taken").value(false));
  }

  @Test
  void aThirdBreakInOneQuarterIsRefused() throws Exception {
    mvc.perform(as(get("/api/me"), TESTER));
    // Three Mondays in one quarter within the 12 weeks offered; a quarter spans 13, so there always are.
    LocalDate target = monday.plusWeeks(8);
    List<LocalDate> sameQuarter = new java.util.ArrayList<>();
    for (LocalDate w = monday.plusWeeks(1); w.isBefore(monday.plusWeeks(13)) && sameQuarter.size() < 3; w = w.plusWeeks(1)) {
      if (w.get(java.time.temporal.IsoFields.QUARTER_OF_YEAR) == target.get(java.time.temporal.IsoFields.QUARTER_OF_YEAR)
          && w.getYear() == target.getYear()) {
        sameQuarter.add(w);
      }
    }
    assertThat(sameQuarter).hasSizeGreaterThanOrEqualTo(3);
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + sameQuarter.get(0) + "\"}").andExpect(status().isOk());
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + sameQuarter.get(1) + "\"}").andExpect(status().isOk());
    send(post("/api/breaks"), TESTER, "{\"weekStart\": \"" + sameQuarter.get(2) + "\"}").andExpect(status().isConflict());
  }

  @Test
  void rewardsFollowWhatWasDoneAndUndoTakesTheStarBack() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER));
    mvc.perform(as(get("/api/rewards"), TESTER))
        .andExpect(jsonPath("$.stars").value(0))
        .andExpect(jsonPath("$.freezes").value(1));
    send(post("/api/units/db.sql.joins/attempts"), TESTER, "{\"rating\": \"easy\"}");
    mvc.perform(as(get("/api/rewards"), TESTER))
        .andExpect(jsonPath("$.stars").value(2))
        .andExpect(jsonPath("$.starsThisWeek").value(2));
    mvc.perform(as(delete("/api/units/db.sql.joins/attempts/latest"), TESTER).with(RealCsrf.token(mvc, TESTER)));
    mvc.perform(as(get("/api/rewards"), TESTER)).andExpect(jsonPath("$.stars").value(0));
  }

  private static final String CHANGELOG = "# Test release\n\n## Suggested placement\n"
      + "| Unit | Tester | Other |\n| --- | --- | --- |\n| db.sql.joins | end of track | now |\n";

  @Test
  void theDashboardShowsCoverageWeakAreasAndTheReleaseWithSuggestedPlacements() throws Exception {
    jdbc.update("update curriculum_release set changelog = ?", CHANGELOG);
    send(post("/api/units/dsa.window.concept/attempts"), TESTER, "{\"rating\": \"good\"}");
    mvc.perform(as(get("/api/dashboard"), TESTER))
        .andExpect(jsonPath("$.coverage[?(@.domainId == 'dsa')].done").value(1))
        .andExpect(jsonPath("$.coverage[?(@.domainId == 'dsa')].total").value(2))
        .andExpect(jsonPath("$.weakAreas[0].unitsLeft").isNumber())
        .andExpect(jsonPath("$.whatChanged.version").value("2026.39.1"))
        .andExpect(jsonPath("$.whatChanged.units[?(@.unitId == 'db.sql.joins')].placement").value("end-of-track"))
        .andExpect(jsonPath("$.whatChanged.units[?(@.unitId == 'db.sql.joins')].chosen").value(false));
    mvc.perform(as(get("/api/dashboard"), OTHER))
        .andExpect(jsonPath("$.whatChanged.units[?(@.unitId == 'db.sql.joins')].placement").value("now"))
        .andExpect(jsonPath("$.whatChanged.units[?(@.unitId == 'db.sql.tester-only')]").isEmpty());
  }

  @Test
  void laterKeepsAUnitOutOfPlansAndNowPutsItInThisWeek() throws Exception {
    jdbc.update("update curriculum_release set changelog = ?", CHANGELOG);
    send(put("/api/me/week"), TESTER, SETTINGS);
    // Suggested "end of track" for tester: left out of the plan.
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')]").isEmpty());

    // Changing it to "now" adds it to this week's plan straight away...
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"now\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.whatChanged.units[?(@.unitId == 'db.sql.joins')].chosen").value(true));
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')].reason").value("You chose to start it now"));

    // ...and back to "next week" takes it out again.
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"next-week\"}");
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')]").isEmpty());

    // A rebuilt week keeps honouring the choice: "now" goes first, and can still be taken back out.
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"now\"}");
    mvc.perform(as(delete("/api/plan"), TESTER).with(RealCsrf.token(mvc, TESTER)));
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')].reason",
            hasItem(startsWith("You chose to start it now; Databases is"))));
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"end-of-track\"}");
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')]").isEmpty());
  }

  @Test
  void placementsAreChecked() throws Exception {
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"someday\"}")
        .andExpect(status().isBadRequest());
    send(put("/api/placements"), OTHER, "{\"unitId\": \"db.sql.tester-only\", \"choice\": \"now\"}")
        .andExpect(status().isNotFound());
  }
}
