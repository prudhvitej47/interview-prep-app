package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
 * My projects, through the real security chain: importing a question file (and importing it again),
 * answering and rating, the review queue and the export. Everything in the fixtures is invented;
 * this repository is public.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProjectsApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final String UNIT = "ds.transactions.idempotency";

  private static final String FILE = """
      { "version": 1,
        "projects": [
          { "key": "ledger", "name": "Example ledger", "summary": "Built the **posting** service.",
            "topics": ["hld.payments"],
            "questions": [
              { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through the ledger.",
                "probes": "Where does a posting start?", "strong_answer": "Accounts, entries, balances.",
                "units": ["%s"], "minutes": 20 },
              { "key": "ledger-hot", "rung": "scale", "prompt": "What fails first at 10x?",
                "units": ["%s", "no.such.unit"] } ] },
          { "key": "gateway", "name": "Example gateway", "summary": "Routed the calls.",
            "questions": [
              { "key": "gateway-why", "rung": "why", "prompt": "Why a gateway at all?" } ] } ] }
      """.formatted(UNIT, UNIT);

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('distributed', 'Distributed', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order)"
        + " values ('ds.transactions', 'distributed', null, 'Transactions', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.39.1', 'sha256:t', 't') returning id", Long.class);
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin,"
        + " visibility, state, version, body, content_hash, release_id) values (?, 'ds.transactions',"
        + " 'concept', 'Idempotency keys', 3, 30, '{hld}', 'synthesized', 'shared', 'draft', 1,"
        + " '{\"markdown\": \"x\"}', 'h', ?)", UNIT, release);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  private ResultActions importAs(String login, String file) throws Exception {
    return mvc.perform(as(post("/api/projects/import"), login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(file));
  }

  private long questionId(String key) {
    return jdbc.queryForObject("select id from project_question where key = ?", Long.class, key);
  }

  private long projectId(String key) {
    return jdbc.queryForObject("select id from experience_project where key = ?", Long.class, key);
  }

  private ResultActions answer(String login, long question, String answer) throws Exception {
    return mvc.perform(as(put("/api/projects/questions/" + question + "/answer"), login)
        .with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\": \"" + answer + "\"}"));
  }

  private ResultActions rate(String login, long question, String rating) throws Exception {
    return mvc.perform(as(post("/api/projects/questions/" + question + "/attempts"), login)
        .with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": \"" + rating + "\"}"));
  }

  @Nested
  class Importing {

    @Test
    void aFirstImportAddsEverythingAndReportsUnknownUnits() throws Exception {
      importAs(TESTER, FILE)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.projectsAdded").value(2))
          .andExpect(jsonPath("$.questionsAdded").value(3))
          .andExpect(jsonPath("$.questionsUpdated").value(0))
          .andExpect(jsonPath("$.unknownUnits[0]").value("no.such.unit"))
          .andExpect(jsonPath("$.unknownUnits.length()").value(1));
      // The unknown id is dropped from the links; the known one stays.
      assertThat(jdbc.queryForObject("select array_to_string(unit_ids, ',') from project_question"
          + " where key = 'ledger-hot'", String.class)).isEqualTo(UNIT);
    }

    @Test
    void theListShowsProjectsInFileOrderWithTheirCounts() throws Exception {
      importAs(TESTER, FILE);
      mvc.perform(as(get("/api/projects"), TESTER))
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].name").value("Example ledger"))
          .andExpect(jsonPath("$[0].questions").value(2))
          .andExpect(jsonPath("$[0].answered").value(0))
          .andExpect(jsonPath("$[1].name").value("Example gateway"));
    }

    @Test
    void aProjectPageHasItsLadderInOrderWithUnitTitles() throws Exception {
      importAs(TESTER, FILE);
      mvc.perform(as(get("/api/projects/" + projectId("ledger")), TESTER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.summary").value("Built the **posting** service."))
          .andExpect(jsonPath("$.questions[0].rung").value("walkthrough"))
          .andExpect(jsonPath("$.questions[0].minutes").value(20))
          .andExpect(jsonPath("$.questions[0].strongAnswer").value("Accounts, entries, balances."))
          .andExpect(jsonPath("$.questions[0].units[0].title").value("Idempotency keys"))
          .andExpect(jsonPath("$.questions[1].minutes").value(15))
          .andExpect(jsonPath("$.questions[1].progress.attempts").value(0));
    }

    @Test
    void reimportingUpdatesInPlaceRetiresWhatIsMissingAndKeepsAnswersAndRatings() throws Exception {
      importAs(TESTER, FILE);
      long walk = questionId("ledger-walk");
      answer(TESTER, walk, "Accounts first.").andExpect(status().isOk());
      rate(TESTER, walk, "good").andExpect(status().isOk());

      // Reworded prompt, ledger-hot and the whole gateway project gone, one new question.
      String next = """
          { "version": 1, "projects": [
            { "key": "ledger", "name": "Example ledger", "summary": "Built the **posting** service.",
              "topics": ["hld.payments"],
              "questions": [
                { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through it, end to end.",
                  "probes": "Where does a posting start?", "strong_answer": "Accounts, entries, balances.",
                  "units": ["%s"], "minutes": 20 },
                { "key": "ledger-change", "rung": "change", "prompt": "What would you change?" } ] } ] }
          """.formatted(UNIT);
      importAs(TESTER, next)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.projectsAdded").value(0))
          .andExpect(jsonPath("$.projectsUpdated").value(0))
          .andExpect(jsonPath("$.projectsRetired").value(1))
          .andExpect(jsonPath("$.questionsAdded").value(1))
          .andExpect(jsonPath("$.questionsUpdated").value(1))
          .andExpect(jsonPath("$.questionsRetired").value(1))
          .andExpect(jsonPath("$.unknownUnits.length()").value(0));

      assertThat(questionId("ledger-walk")).isEqualTo(walk);
      mvc.perform(as(get("/api/projects/" + projectId("ledger")), TESTER))
          .andExpect(jsonPath("$.questions.length()").value(2))
          .andExpect(jsonPath("$.questions[0].prompt").value("Walk me through it, end to end."))
          .andExpect(jsonPath("$.questions[0].answer").value("Accounts first."))
          .andExpect(jsonPath("$.questions[0].progress.attempts").value(1))
          .andExpect(jsonPath("$.questions[1].rung").value("change"));
      mvc.perform(as(get("/api/projects"), TESTER)).andExpect(jsonPath("$.length()").value(1));
      // Retired, not deleted: the dropped question, and the dropped project with its question inside.
      assertThat(jdbc.queryForObject("select count(*) from project_question", Integer.class)).isEqualTo(4);
      assertThat(jdbc.queryForObject("select retired from experience_project where key = 'gateway'",
          Boolean.class)).isTrue();
      mvc.perform(as(get("/api/projects/" + projectId("gateway")), TESTER)).andExpect(status().isNotFound());
    }

    @Test
    void theSameFileTwiceChangesNothing() throws Exception {
      importAs(TESTER, FILE);
      importAs(TESTER, FILE)
          .andExpect(jsonPath("$.projectsAdded").value(0))
          .andExpect(jsonPath("$.projectsUpdated").value(0))
          .andExpect(jsonPath("$.questionsAdded").value(0))
          .andExpect(jsonPath("$.questionsUpdated").value(0))
          .andExpect(jsonPath("$.questionsRetired").value(0));
    }

    @Test
    void aRetiredQuestionComesBackWithItsAnswerWhenAFileBringsItBack() throws Exception {
      importAs(TESTER, FILE);
      answer(TESTER, questionId("gateway-why"), "Fewer hops.");
      importAs(TESTER, FILE.replace("\"gateway\"", "\"gateway-renamed\""));
      importAs(TESTER, FILE).andExpect(jsonPath("$.projectsUpdated").value(1));
      mvc.perform(as(get("/api/projects/" + projectId("gateway")), TESTER))
          .andExpect(jsonPath("$.questions[0].answer").value("Fewer hops."));
    }

    @Test
    void aBadFileIsRefusedWithEveryProblemListedAndNothingChanges() throws Exception {
      importAs(TESTER, """
          { "version": 2, "projects": [
            { "key": "has space", "name": "",
              "questions": [ { "key": "q", "rung": "vibes", "prompt": "Why?", "minutes": 500 },
                             { "key": "q", "rung": "why", "prompt": "Why again?" } ] } ] }
          """)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.problems[0]").value("\"version\" must be 1."))
          .andExpect(jsonPath("$.problems.length()").value(6));
      assertThat(jdbc.queryForObject("select count(*) from experience_project", Integer.class)).isZero();
    }

    @Test
    void notJsonIsRefusedWithoutEchoingIt() throws Exception {
      importAs(TESTER, "{ \"version\": 1, secret words")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.problems[0]").value("The file is not valid JSON."));
    }

    @Test
    void anEmptyFileWouldRetireEverythingSoItIsRefused() throws Exception {
      importAs(TESTER, FILE);
      importAs(TESTER, "{ \"version\": 1, \"projects\": [] }").andExpect(status().isUnprocessableContent());
      mvc.perform(as(get("/api/projects"), TESTER)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void tooManyProjectsOrQuestionsAreRefused() throws Exception {
      StringBuilder projects = new StringBuilder();
      for (int i = 0; i < 31; i++) {
        projects.append(i == 0 ? "" : ",").append("{\"key\": \"p").append(i).append("\", \"name\": \"P")
            .append(i).append("\"}");
      }
      importAs(TESTER, "{\"version\": 1, \"projects\": [" + projects + "]}")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.problems[0]").value("At most 30 projects; this file has 31."));

      StringBuilder questions = new StringBuilder();
      for (int i = 0; i < 31; i++) {
        questions.append(i == 0 ? "" : ",").append("{\"key\": \"q").append(i)
            .append("\", \"rung\": \"why\", \"prompt\": \"Why?\"}");
      }
      importAs(TESTER, "{\"version\": 1, \"projects\": [{\"key\": \"p\", \"name\": \"P\", \"questions\": ["
          + questions + "]}]}")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.problems[0]").value("Project \"p\": at most 30 questions; it has 31."));
    }

    @Test
    void anOverlongFieldOrFileIsRefused() throws Exception {
      importAs(TESTER, "{\"version\": 1, \"projects\": [{\"key\": \"p\", \"name\": \"P\", \"questions\": ["
          + "{\"key\": \"q\", \"rung\": \"why\", \"prompt\": \"" + "x".repeat(2_001) + "\"}]}]}")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.problems[0]")
              .value("Project \"p\", question 1 (\"q\"): \"prompt\" is longer than 2000 characters."));
      importAs(TESTER, "{\"version\": 1, \"pad\": \"" + "x".repeat(1024 * 1024) + "\"}")
          .andExpect(status().isContentTooLarge());
    }

    @Test
    void importingNeedsTheCsrfToken() throws Exception {
      mvc.perform(as(post("/api/projects/import"), TESTER)
              .contentType(MediaType.APPLICATION_JSON).content(FILE))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  class Scoping {

    @Test
    void theOtherLearnerSeesNoneOfItAndGetsNotFoundForEveryId() throws Exception {
      importAs(TESTER, FILE);
      long project = projectId("ledger");
      long question = questionId("ledger-walk");

      mvc.perform(as(get("/api/projects"), OTHER)).andExpect(jsonPath("$.length()").value(0));
      mvc.perform(as(get("/api/projects/" + project), OTHER)).andExpect(status().isNotFound());
      answer(OTHER, question, "mine now").andExpect(status().isNotFound());
      rate(OTHER, question, "good").andExpect(status().isNotFound());
      mvc.perform(as(delete("/api/projects/questions/" + question + "/attempts/latest"), OTHER)
              .with(RealCsrf.token(mvc, OTHER)))
          .andExpect(status().isNotFound());
      mvc.perform(as(get("/api/projects/export"), OTHER)).andExpect(jsonPath("$.projects.length()").value(0));
      assertThat(jdbc.queryForObject("select answer from project_question where id = ?", String.class, question))
          .isEmpty();
    }

    @Test
    void eachLearnerHasTheirOwnProjectsUnderTheSameKeys() throws Exception {
      importAs(TESTER, FILE);
      importAs(OTHER, FILE).andExpect(jsonPath("$.projectsAdded").value(2));
      assertThat(jdbc.queryForObject("select count(*) from experience_project", Integer.class)).isEqualTo(4);
    }
  }

  @Nested
  class AnsweringAndRating {

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Test
    void anAnswerSavesAndClears() throws Exception {
      importAs(TESTER, FILE);
      long q = questionId("ledger-walk");
      answer(TESTER, q, "Accounts first.")
          .andExpect(jsonPath("$.answer").value("Accounts first."))
          .andExpect(jsonPath("$.answeredAt").isNotEmpty());
      mvc.perform(as(get("/api/projects"), TESTER)).andExpect(jsonPath("$[0].answered").value(1));
      answer(TESTER, q, "  ").andExpect(jsonPath("$.answer").value("")).andExpect(jsonPath("$.answeredAt").isEmpty());
    }

    @Test
    void anOverlongAnswerIsRefused() throws Exception {
      importAs(TESTER, FILE);
      answer(TESTER, questionId("ledger-walk"), "x".repeat(20_001)).andExpect(status().isBadRequest());
    }

    @Test
    void ratingSchedulesTheNextRehearsalOnTheUnitLadderAndUndoTakesItBack() throws Exception {
      importAs(TESTER, FILE);
      long q = questionId("ledger-walk");
      rate(TESTER, q, "good")
          .andExpect(jsonPath("$.attempts").value(1))
          .andExpect(jsonPath("$.dueOn").value(today.plusDays(1).toString()))
          .andExpect(jsonPath("$.reviewDue").value(false));
      // Good puts it on the ladder's first step, easy climbs two more: 7 days.
      rate(TESTER, q, "easy").andExpect(jsonPath("$.dueOn").value(today.plusDays(7).toString()));
      mvc.perform(as(delete("/api/projects/questions/" + q + "/attempts/latest"), TESTER)
              .with(RealCsrf.token(mvc, TESTER)))
          .andExpect(jsonPath("$.attempts").value(1))
          .andExpect(jsonPath("$.lastRating").value("good"));
      rate(TESTER, q, "done").andExpect(status().isBadRequest());
      // Unit progress is untouched: project rehearsals are kept apart from units.
      assertThat(jdbc.queryForObject("select count(*) from attempt", Integer.class)).isZero();
    }

    @Test
    void theReviewQueueHoldsDueQuestionsOldestFirst() throws Exception {
      importAs(TESTER, FILE);
      mvc.perform(as(get("/api/projects/reviews"), TESTER)).andExpect(jsonPath("$.due.length()").value(0));
      rehearsedDaysAgo("ledger-hot", "good", 5);     // due 4 days ago
      rehearsedDaysAgo("gateway-why", "good", 1);    // due today
      rehearsedDaysAgo("ledger-walk", "easy", 0);    // due in 3 days

      mvc.perform(as(get("/api/projects/reviews"), TESTER))
          .andExpect(jsonPath("$.due.length()").value(2))
          .andExpect(jsonPath("$.due[0].questionId").value(questionId("ledger-hot")))
          .andExpect(jsonPath("$.due[0].projectName").value("Example ledger"))
          .andExpect(jsonPath("$.due[0].dueOn").value(today.minusDays(4).toString()))
          .andExpect(jsonPath("$.due[1].prompt").value("Why a gateway at all?"))
          .andExpect(jsonPath("$.nextDueOn").value(today.plusDays(3).toString()));
      mvc.perform(as(get("/api/projects"), TESTER))
          .andExpect(jsonPath("$[0].due").value(1))
          .andExpect(jsonPath("$[1].due").value(1));
      mvc.perform(as(get("/api/projects/reviews"), OTHER)).andExpect(jsonPath("$.due.length()").value(0));
    }

    private void rehearsedDaysAgo(String key, String rating, int days) {
      jdbc.update("insert into project_attempt (learner_id, question_id, rating, created_at)"
          + " select id, ?, ?, now() - make_interval(days => ?) from learner where slug = 'tester'",
          questionId(key), rating, days);
    }
  }

  @Nested
  class Exporting {

    @Test
    void theExportIsTheImportFormatWithAnswersAndRatingsAndImportsBackUnchanged() throws Exception {
      importAs(TESTER, FILE);
      long walk = questionId("ledger-walk");
      answer(TESTER, walk, "Accounts first.");
      rate(TESTER, walk, "hard");

      String exported = mvc.perform(as(get("/api/projects/export"), TESTER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.version").value(1))
          .andExpect(jsonPath("$.projects[0].key").value("ledger"))
          .andExpect(jsonPath("$.projects[0].topics[0]").value("hld.payments"))
          .andExpect(jsonPath("$.projects[0].questions[0].strong_answer").value("Accounts, entries, balances."))
          .andExpect(jsonPath("$.projects[0].questions[0].answer").value("Accounts first."))
          .andExpect(jsonPath("$.projects[0].questions[0].ratings[0].rating").value("hard"))
          .andExpect(jsonPath("$.projects[0].questions[1].units.length()").value(1))
          .andReturn().getResponse().getContentAsString();

      importAs(TESTER, exported)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.projectsUpdated").value(0))
          .andExpect(jsonPath("$.questionsAdded").value(0))
          .andExpect(jsonPath("$.questionsUpdated").value(0))
          .andExpect(jsonPath("$.questionsRetired").value(0));
      mvc.perform(as(get("/api/projects/" + projectId("ledger")), TESTER))
          .andExpect(jsonPath("$.questions[0].answer").value("Accounts first."))
          .andExpect(jsonPath("$.questions[0].progress.attempts").value(1));
    }
  }
}
