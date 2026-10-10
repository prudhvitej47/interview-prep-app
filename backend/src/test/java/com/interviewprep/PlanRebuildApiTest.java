package com.interviewprep;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Rebuilding mid-week, on a fixed Wednesday (30 Sep 2026, noon in India), so the rules that depend
 * on the weekday hold whichever day the tests run. Attempts are written with their own days.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PlanRebuildApiTest.Wednesday.class)
class PlanRebuildApiTest extends PostgresTestBase {

  @TestConfiguration
  static class Wednesday {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-09-30T06:30:00Z"), ZoneOffset.UTC);
    }
  }

  private static final String TESTER = "tester@example.com";
  // 40 hours over every day: from Wednesday, five days of seven are left, 1543 minutes.
  private static final String EVERY_DAY = "{\"hoursPerWeek\": 40, \"studyDays\": [1, 2, 3, 4, 5, 6, 7], \"weights\": {}}";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('databases', 'Databases', 40, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values"
        + " ('db.sql', 'databases', null, 'SQL', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha,"
        + " units_added) values ('2026.39.1', 'sha256:t', 't', 2) returning id", Long.class);
    for (String id : new String[] {"db.sql.joins", "db.sql.groupby"}) {
      jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin,"
          + " visibility, state, version, body, content_hash, release_id) values (?, 'db.sql', 'sql', ?, 2, 20,"
          + " '{}', 'synthesized', 'shared', 'draft', 1, '{}'::jsonb, 'h', ?)", id, "Title of " + id, release);
    }
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
    return mvc.perform(request.header("Tailscale-User-Login", TESTER).with(RealCsrf.token(mvc, TESTER))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions week() throws Exception {
    return mvc.perform(get("/api/plan").header("Tailscale-User-Login", TESTER));
  }

  /** As if the learner did the unit on Tuesday, before the plan's days. */
  private void doneOnTuesday(String unit) {
    jdbc.update("insert into attempt (learner_id, unit_id, rating, created_at) select id, ?, 'good',"
        + " '2026-09-29T10:00:00+05:30'::timestamptz from learner where slug = 'tester'", unit);
  }

  @Test
  void workDoneOnAnEarlierDayIsAddedToTheDaysLeft() throws Exception {
    send(put("/api/me/week"), EVERY_DAY).andExpect(status().isOk());
    week().andExpect(jsonPath("$.plan.plannedMinutes").value(1543));
    doneOnTuesday("db.sql.joins");

    send(delete("/api/plan"), "").andExpect(status().isOk());
    week()
        .andExpect(jsonPath("$.plan.plannedMinutes").value(1543 + 20))
        .andExpect(jsonPath("$.plan.doneMinutes").value(20))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.joins')].done").value(true))
        .andExpect(jsonPath("$.plan.items[?(@.unitId == 'db.sql.groupby')].day").value(3));
  }

  @Test
  void rebuildingAfterTheLastStudyDayKeepsWhatWasDone() throws Exception {
    send(put("/api/me/week"), EVERY_DAY).andExpect(status().isOk());
    week().andExpect(jsonPath("$.plan.items.length()").value(2));
    doneOnTuesday("db.sql.joins");
    // Only Monday and Tuesday now: no study day is left this week.
    send(put("/api/me/week"), "{\"hoursPerWeek\": 40, \"studyDays\": [1, 2], \"weights\": {}}")
        .andExpect(status().isOk());

    send(delete("/api/plan"), "").andExpect(status().isOk());
    week()
        .andExpect(jsonPath("$.plan.items.length()").value(1))
        .andExpect(jsonPath("$.plan.items[0].unitId").value("db.sql.joins"))
        .andExpect(jsonPath("$.plan.items[0].done").value(true))
        .andExpect(jsonPath("$.plan.plannedMinutes").value(20))
        .andExpect(jsonPath("$.plan.goalMinutes").value(16))
        .andExpect(jsonPath("$.plan.doneMinutes").value(20));
  }
}
