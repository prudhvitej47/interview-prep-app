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
    // As the loader records it: the four units below arrived in this release.
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha,"
        + " units_added) values ('2026.39.1', 'sha256:t', 't', 4) returning id", Long.class);
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
  void notForMeKeepsATopicOrAUnitOutOfThatLearnersPlansOnly() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    send(put("/api/me/week"), OTHER, SETTINGS);
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"dsa.window\", \"excluded\": true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topics[0]").value("dsa.window"));
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"unit\", \"id\": \"db.sql.joins\", \"excluded\": true}")
        .andExpect(jsonPath("$.units[0]").value("db.sql.joins"));

    // Only the tester's own project unit is left; the other learner's week is untouched.
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items.length()").value(1))
        .andExpect(jsonPath("$.plan.items[0].unitId").value("db.sql.tester-only"));
    mvc.perform(as(get("/api/plan"), OTHER)).andExpect(jsonPath("$.plan.items.length()").value(3));

    // Clearing the mark brings the topic back from the next plan drawn.
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"dsa.window\", \"excluded\": false}")
        .andExpect(jsonPath("$.topics.length()").value(0));
    mvc.perform(as(delete("/api/plan"), TESTER).with(RealCsrf.token(mvc, TESTER))).andExpect(status().isOk());
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.items.length()").value(3));
  }

  @Test
  void theSharePreviewIsWhatThePlanWouldUseAndSavesNothing() throws Exception {
    // Unrated, both areas sit at neutral strength, so the shares follow the default weights 60:40.
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].percent").value(60))
        .andExpect(jsonPath("$[?(@.domainId == 'databases')].percent").value(40));
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {\"dsa\": 20}}")
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].percent").value(33));
    // 0 leaves an area out, and so does keeping all of its topics out of this learner's plans.
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {\"databases\": 0}}")
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].percent").value(100));
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"dsa.window\", \"excluded\": true}");
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {}}")
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].domainId").value("databases"));
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {\"nope\": 5}}").andExpect(status().isBadRequest());
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {\"dsa\": 101}}").andExpect(status().isBadRequest());
    assertThat(plans()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from learner_weight", Integer.class)).isZero();
  }

  @Test
  void theSharePreviewShowsWhatTheWeightsAloneWouldGiveAndTheLearnersStrength() throws Exception {
    // Unrated: neutral strength everywhere, so the weakness factor moves nothing.
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {}}")
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].unboostedPercent").value(60))
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].strength").value(2.5));
    // Weak in DSA (1/5), strong in databases (4/5): 60 × 1.34 against 40 × 0.86.
    mvc.perform(as(get("/api/me"), TESTER));
    jdbc.update("insert into learner_competency (learner_id, scope, scope_id, rating, source)"
        + " select id, 'domain', 'dsa', 1, 'self-rating' from learner where slug = 'tester'");
    jdbc.update("insert into learner_competency (learner_id, scope, scope_id, rating, source)"
        + " select id, 'domain', 'databases', 4, 'self-rating' from learner where slug = 'tester'");
    send(post("/api/plan/shares"), TESTER, "{\"weights\": {}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].percent").value(70))
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].unboostedPercent").value(60))
        .andExpect(jsonPath("$[?(@.domainId == 'dsa')].strength").value(1.0))
        .andExpect(jsonPath("$[?(@.domainId == 'databases')].percent").value(30))
        .andExpect(jsonPath("$[?(@.domainId == 'databases')].unboostedPercent").value(40))
        .andExpect(jsonPath("$[?(@.domainId == 'databases')].strength").value(4.0));
  }

  @Test
  void notForMeIsChecked() throws Exception {
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"domain\", \"id\": \"dsa\", \"excluded\": true}")
        .andExpect(status().isBadRequest());
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"unit\", \"id\": \"nope\", \"excluded\": true}")
        .andExpect(status().isBadRequest());
    // Another learner's private unit is unknown to this one.
    send(put("/api/me/not-for-me"), OTHER, "{\"scope\": \"unit\", \"id\": \"db.sql.tester-only\", \"excluded\": true}")
        .andExpect(status().isBadRequest());
    send(put("/api/me/not-for-me"), TESTER, "{\"scope\": \"topic\", \"id\": \"dsa.window\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void aReleaseThatChangedNoUnitsLeavesTheDashboardOnTheLastOneThatDid() throws Exception {
    jdbc.update("insert into curriculum_release (version, bundle_digest, git_sha) values ('2026.39.2', 'sha256:e', 'e')");
    mvc.perform(as(get("/api/dashboard"), TESTER))
        .andExpect(jsonPath("$.whatChanged.version").value("2026.39.1"));
  }

  @Test
  void placementsAreChecked() throws Exception {
    send(put("/api/placements"), TESTER, "{\"unitId\": \"db.sql.joins\", \"choice\": \"someday\"}")
        .andExpect(status().isBadRequest());
    send(put("/api/placements"), OTHER, "{\"unitId\": \"db.sql.tester-only\", \"choice\": \"now\"}")
        .andExpect(status().isNotFound());
  }

  /** An earlier week's plan, written as the planner would have: one learn item per unit. */
  private long earlierPlan(LocalDate week, String... itemsAndReasons) {
    long plan = jdbc.queryForObject("insert into week_plan (learner_id, week_start, planned_minutes, goal_minutes,"
        + " planner_version) select id, ?, 100, 80, 1 from learner where slug = 'tester' returning id", Long.class, week);
    for (int i = 0; i < itemsAndReasons.length; i += 2) {
      jdbc.update("insert into plan_item (plan_id, unit_id, day, kind, minutes, reason, sort_order)"
          + " values (?, ?, 1, 'learn', 20, ?, ?)", plan, itemsAndReasons[i], itemsAndReasons[i + 1], i);
    }
    return plan;
  }

  @Test
  void unfinishedItemsFromTheLastPlanGetNoCarryOverPriority() throws Exception {
    // Plenty of time, so every unit is planned: the test is about how they are marked.
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 40, \"studyDays\": [1, 2, 3, 4, 5, 6, 7], \"weights\": {}}");
    earlierPlan(monday.minusWeeks(1), "db.sql.joins", "Databases is 40% of this week.",
        "dsa.window.concept", "DSA is 60% of this week.",
        "dsa.window.p1", "DSA is 60% of this week.");
    send(post("/api/units/dsa.window.concept/attempts"), TESTER, "{\"rating\": \"good\"}");

    mvc.perform(as(get("/api/plan"), TESTER))
        // Unfinished: back in the pool, planned on rank and share, not marked as carried over.
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')]").isNotEmpty())
        .andExpect(jsonPath("$.plan.items[?(@.kind == 'learn')].reason",
            org.hamcrest.Matchers.not(hasItem(startsWith("Carried over")))))
        // Done since: no longer learning.
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'dsa.window.concept' && @.kind == 'learn')]").isEmpty());
  }

  private int today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue();
  }

  private com.jayway.jsonpath.DocumentContext week() throws Exception {
    return com.jayway.jsonpath.JsonPath.parse(
        mvc.perform(as(get("/api/plan"), TESTER)).andReturn().getResponse().getContentAsString());
  }

  private void rebuild() throws Exception {
    mvc.perform(as(delete("/api/plan"), TESTER).with(RealCsrf.token(mvc, TESTER))).andExpect(status().isOk());
  }

  @Test
  void rebuildingKeepsWhatWasDoneThisWeekAndTheWeeksScore() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    var before = week();
    send(post("/api/units/db.sql.joins/attempts"), TESTER, "{\"rating\": \"good\"}");
    send(post("/api/units/dsa.window.concept/attempts"), TESTER, "{\"rating\": \"good\"}");
    var done = week();
    assertThat(done.read("$.plan.doneMinutes", Integer.class)).isEqualTo(50);

    rebuild();
    assertThat(plans()).isEqualTo(1);
    var after = week();
    // Done work counts the same, and today's share already held it, so the week asks no more.
    assertThat(after.read("$.plan.doneMinutes", Integer.class)).isEqualTo(50);
    assertThat(after.read("$.plan.plannedMinutes", Integer.class))
        .isEqualTo(before.read("$.plan.plannedMinutes", Integer.class));
    assertThat(after.read("$.plan.goalMinutes", Integer.class))
        .isEqualTo(before.read("$.plan.goalMinutes", Integer.class));
    for (String unit : List.of("db.sql.joins", "dsa.window.concept")) {
      String item = "$.plan.items[?(@.unitId == '" + unit + "')]";
      assertThat(after.read(item + ".done", List.class)).containsExactly(true);
      assertThat(after.read(item + ".day", List.class)).isEqualTo(before.read(item + ".day", List.class));
      assertThat(after.read(item + ".reason", List.class)).isEqualTo(before.read(item + ".reason", List.class));
    }
    // Each unit once: what was done is not planned again as a review.
    List<String> units = after.read("$.plan.items[*].unitId");
    assertThat(units).doesNotHaveDuplicates().hasSize(4);
    assertThat(after.read("$.plan.notes", List.class))
        .contains("Rebuilt: the 2 items you had already done stay in it and count towards the week.");
    // The result gamification and the next week's size read agrees.
    assertThat(jdbc.queryForObject("select planned_minutes from week_plan", Integer.class))
        .isEqualTo(before.read("$.plan.plannedMinutes", Integer.class));
  }

  @Test
  void rebuildingAfterTheLastStudyDayKeepsWhatWasDone() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(today() > 1, "needs a study day before today");
    send(put("/api/me/week"), TESTER, SETTINGS);
    mvc.perform(as(get("/api/plan"), TESTER));
    send(post("/api/units/db.sql.joins/attempts"), TESTER, "{\"rating\": \"good\"}");
    List<Integer> before = java.util.stream.IntStream.range(1, today()).boxed().toList();
    send(put("/api/me/week"), TESTER, "{\"hoursPerWeek\": 40, \"studyDays\": " + before + ", \"weights\": {}}")
        .andExpect(status().isOk());

    rebuild();
    mvc.perform(as(get("/api/plan"), TESTER))
        .andExpect(jsonPath("$.plan.items.length()").value(1))
        .andExpect(jsonPath("$.plan.items[0].unitId").value("db.sql.joins"))
        .andExpect(jsonPath("$.plan.items[0].done").value(true))
        .andExpect(jsonPath("$.plan.plannedMinutes").value(20))
        .andExpect(jsonPath("$.plan.goalMinutes").value(16))
        .andExpect(jsonPath("$.plan.doneMinutes").value(20));
  }

  @Test
  void addingMoreIsCheckedAndNeedsTheWholePlanDone() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 45}").andExpect(status().isBadRequest());
    send(post("/api/plan/extra"), TESTER, "{}").andExpect(status().isBadRequest());
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.canAddMore").value(false));
    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 30}").andExpect(status().isConflict());

    // All done, and nothing else to learn: still no.
    for (String unit : List.of("dsa.window.concept", "dsa.window.p1", "db.sql.joins", "db.sql.tester-only")) {
      send(post("/api/units/" + unit + "/attempts"), TESTER, "{\"rating\": \"good\"}").andExpect(status().isOk());
    }
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.canAddMore").value(false));
    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 90}").andExpect(status().isConflict());
  }

  @Test
  void finishingEarlyAddsMoreOnTodayWithoutRaisingTheWeek() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    var before = week();
    for (String unit : List.of("dsa.window.concept", "dsa.window.p1", "db.sql.joins", "db.sql.tester-only")) {
      send(post("/api/units/" + unit + "/attempts"), TESTER, "{\"rating\": \"good\"}");
    }
    // Arrived after the plan was made, so only an extra can bring it into this week.
    long release = jdbc.queryForObject("select id from curriculum_release", Long.class);
    unit("db.sql.groupby", "db.sql", "sql", 20, "shared", release);
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.canAddMore").value(true));

    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 30}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plan.items.length()").value(5))
        .andExpect(jsonPath("$.plan.items[4].unitId").value("db.sql.groupby"))
        .andExpect(jsonPath("$.plan.items[4].day").value(today()))
        .andExpect(jsonPath("$.plan.items[4].kind").value("learn"))
        .andExpect(jsonPath("$.plan.items[4].reason", startsWith("Added after you finished early; Databases is")))
        .andExpect(jsonPath("$.plan.plannedMinutes").value(before.read("$.plan.plannedMinutes", Integer.class)))
        .andExpect(jsonPath("$.plan.goalMinutes").value(before.read("$.plan.goalMinutes", Integer.class)))
        .andExpect(jsonPath("$.plan.canAddMore").value(false));
    // Not done yet, so no more until it is; once done, it counts.
    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 30}").andExpect(status().isConflict());
    send(post("/api/units/db.sql.groupby/attempts"), TESTER, "{\"rating\": \"good\"}");
    mvc.perform(as(get("/api/plan"), TESTER)).andExpect(jsonPath("$.plan.doneMinutes").value(125));
  }

  @Test
  void noMoreInABreakWeek() throws Exception {
    send(put("/api/me/week"), TESTER, SETTINGS);
    jdbc.update("insert into planned_break (learner_id, week_start) select id, ? from learner where slug = 'tester'",
        monday);
    send(post("/api/plan/extra"), TESTER, "{\"minutes\": 30}").andExpect(status().isConflict());
  }
}
