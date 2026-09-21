package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
 * Browsing the curriculum and keeping notes, through the real security chain.
 *
 * <p>The fixture carries the two cases with rules attached: a unit private to "tester", which "other"
 * must not be able to see, count or detect; and a retired unit, which leaves listings but stays
 * reachable because a learner's history may still point at it.
 */
@SpringBootTest
@ActiveProfiles("test")
class CurriculumAndNotesTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final String PRIVATE_UNIT = "ds.transactions.tester-project";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values"
        + " ('hld', 'High-level design', 40, 1), ('distributed', 'Distributed systems', 60, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values"
        + " ('ds.transactions', 'distributed', null, 'Transactions', 0),"
        + " ('ds.transactions.sagas', 'distributed', 'ds.transactions', 'Sagas', 1),"
        + " ('hld.payments', 'hld', null, 'Payments', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.39.1', 'sha256:t', 't') returning id", Long.class);
    unit("ds.transactions.idempotency", "ds.transactions", "concept", "shared", "draft", release,
        "## Why it matters\\nRetries.\\n\\n## How it works\\nKeys.");
    unit("ds.transactions.sagas.intro", "ds.transactions.sagas", "concept", "shared", "draft", release,
        "## Why it matters\\nCompensation.");
    unit(PRIVATE_UNIT, "ds.transactions", "project", "learner:tester", "draft", release,
        "## My system\\nPrivate.");
    unit("ds.transactions.old", "ds.transactions", "concept", "shared", "retired", release,
        "## Why it matters\\nOld.");
    jdbc.update("insert into unit_prereq (unit_id, prereq_id)"
        + " values ('ds.transactions.sagas.intro', 'ds.transactions.idempotency')");
    long source = jdbc.queryForObject("insert into source (kind, title, locator)"
        + " values ('book', 'System Design Interview Vol. 2', 'Ch. 11') returning id", Long.class);
    jdbc.update("insert into unit_source (unit_id, source_id) values ('ds.transactions.idempotency', ?)",
        source);
  }

  private void unit(String id, String topic, String type, String visibility, String state,
      long release, String markdown) {
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds,"
        + " origin, visibility, state, version, body, content_hash, release_id)"
        + " values (?, ?, ?, ?, 3, 30, '{hld,lld}', 'synthesized', ?, ?, 1,"
        + " jsonb_build_object('markdown', ?::text), 'h', ?)",
        id, topic, type, "Title of " + id, visibility, state, markdown.replace("\\n", "\n"), release);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  @Nested
  class Browsing {

    @Test
    void topicsComeInCurriculumOrder() throws Exception {
      // The domain rows were inserted hld first; distributed has the lower sort order.
      mvc.perform(as(get("/api/topics"), TESTER))
          .andExpect(jsonPath("$[0].id").value("ds.transactions"))
          .andExpect(jsonPath("$[1].id").value("ds.transactions.sagas"))
          .andExpect(jsonPath("$[2].id").value("hld.payments"));
    }

    @Test
    void aTopicCountsItsSubtopicsAndSkipsRetiredUnits() throws Exception {
      // idempotency + the saga unit in the subtopic + tester's own private unit. Not the retired one.
      mvc.perform(as(get("/api/topics"), TESTER)).andExpect(jsonPath("$[0].unitCount").value(3));
    }

    @Test
    void aTopicPageListsItsSubtopicsAndItsUnits() throws Exception {
      mvc.perform(as(get("/api/topics/ds.transactions"), OTHER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.domainName").value("Distributed systems"))
          .andExpect(jsonPath("$.subtopics[0].id").value("ds.transactions.sagas"))
          .andExpect(jsonPath("$.subtopics[0].unitCount").value(1))
          .andExpect(jsonPath("$.units.length()").value(1))
          .andExpect(jsonPath("$.units[0].id").value("ds.transactions.idempotency"));
    }

    @Test
    void aUnitPageHasItsBodySourcesAndWhereItSits() throws Exception {
      mvc.perform(as(get("/api/units/ds.transactions.idempotency"), TESTER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.markdown").value("## Why it matters\nRetries.\n\n## How it works\nKeys."))
          .andExpect(jsonPath("$.topicName").value("Transactions"))
          .andExpect(jsonPath("$.domainName").value("Distributed systems"))
          .andExpect(jsonPath("$.rounds[0]").value("hld"))
          .andExpect(jsonPath("$.sources[0].locator").value("Ch. 11"));
    }

    @Test
    void aUnitPageLinksItsPrerequisites() throws Exception {
      mvc.perform(as(get("/api/units/ds.transactions.sagas.intro"), TESTER))
          .andExpect(jsonPath("$.prerequisites[0].id").value("ds.transactions.idempotency"));
    }

    @Test
    void aRetiredUnitLeavesListingsButStaysReachable() throws Exception {
      mvc.perform(as(get("/api/topics/ds.transactions"), TESTER))
          .andExpect(jsonPath("$.units[?(@.id == 'ds.transactions.old')]").isEmpty());
      mvc.perform(as(get("/api/units/ds.transactions.old"), TESTER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("retired"));
    }

    @Test
    void unknownThingsAreNotFound() throws Exception {
      mvc.perform(as(get("/api/units/no.such.unit"), TESTER)).andExpect(status().isNotFound());
      mvc.perform(as(get("/api/topics/no.such.topic"), TESTER)).andExpect(status().isNotFound());
    }

    @Test
    void browsingNeedsAnIdentity() throws Exception {
      mvc.perform(get("/api/units/ds.transactions.idempotency")).andExpect(status().isForbidden());
    }
  }

  @Nested
  class PrivateUnits {

    @Test
    void theOwnerSeesIt() throws Exception {
      mvc.perform(as(get("/api/units/" + PRIVATE_UNIT), TESTER)).andExpect(status().isOk());
    }

    @Test
    void anyoneElseGetsNotFoundNotForbidden() throws Exception {
      // 404, so the other learner cannot even tell it exists.
      mvc.perform(as(get("/api/units/" + PRIVATE_UNIT), OTHER)).andExpect(status().isNotFound());
    }

    @Test
    void itIsNotCountedOrListedForAnyoneElse() throws Exception {
      mvc.perform(as(get("/api/topics"), OTHER)).andExpect(jsonPath("$[0].unitCount").value(2));
      mvc.perform(as(get("/api/topics/ds.transactions"), OTHER))
          .andExpect(jsonPath("$.units[?(@.id == '" + PRIVATE_UNIT + "')]").isEmpty());
    }

    @Test
    void notesCannotBeUsedToProbeForIt() throws Exception {
      mvc.perform(as(get("/api/units/" + PRIVATE_UNIT + "/note"), OTHER)).andExpect(status().isNotFound());
    }
  }

  @Nested
  class Notes {

    private static final String UNIT = "ds.transactions.idempotency";

    private ResultActions save(String login, String body) throws Exception {
      return mvc.perform(as(put("/api/units/" + UNIT + "/note"), login).with(RealCsrf.token(mvc, login))
          .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void thereIsNoNoteUntilOneIsWritten() throws Exception {
      mvc.perform(as(get("/api/units/" + UNIT + "/note"), TESTER))
          .andExpect(jsonPath("$.body").value(""))
          .andExpect(jsonPath("$.updatedAt").isEmpty());
    }

    @Test
    void aSavedNoteReadsBack() throws Exception {
      save(TESTER, "{\"body\": \"Keys live beside the payment row.\"}").andExpect(status().isOk());
      mvc.perform(as(get("/api/units/" + UNIT + "/note"), TESTER))
          .andExpect(jsonPath("$.body").value("Keys live beside the payment row."))
          .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void savingAgainReplacesTheNote() throws Exception {
      save(TESTER, "{\"body\": \"first\"}");
      save(TESTER, "{\"body\": \"second\"}").andExpect(jsonPath("$.body").value("second"));
      assertThat(jdbc.queryForObject("select count(*) from note", Integer.class)).isEqualTo(1);
    }

    @Test
    void eachLearnersNotesAreTheirsAlone() throws Exception {
      save(TESTER, "{\"body\": \"mine\"}");
      mvc.perform(as(get("/api/units/" + UNIT + "/note"), OTHER)).andExpect(jsonPath("$.body").value(""));
    }

    @Test
    void clearingANoteRemovesIt() throws Exception {
      save(TESTER, "{\"body\": \"temporary\"}");
      save(TESTER, "{\"body\": \"  \"}").andExpect(jsonPath("$.body").value(""));
      assertThat(jdbc.queryForObject("select count(*) from note", Integer.class)).isZero();
    }

    @Test
    void anOverlongNoteIsRefused() throws Exception {
      save(TESTER, "{\"body\": \"" + "x".repeat(20_001) + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void savingNeedsTheCsrfToken() throws Exception {
      mvc.perform(as(put("/api/units/" + UNIT + "/note"), TESTER)
              .contentType(MediaType.APPLICATION_JSON).content("{\"body\": \"x\"}"))
          .andExpect(status().isForbidden());
    }

    @Test
    void aNoteOnAnUnknownUnitIsNotFound() throws Exception {
      mvc.perform(as(put("/api/units/no.such.unit/note"), TESTER).with(RealCsrf.token(mvc, TESTER))
              .contentType(MediaType.APPLICATION_JSON).content("{\"body\": \"x\"}"))
          .andExpect(status().isNotFound());
    }
  }
}
