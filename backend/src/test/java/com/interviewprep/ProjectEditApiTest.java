package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * My projects edited in the app: adding and changing projects and questions, hiding and restoring
 * them, reordering, and how that sits with importing a file. Everything in the fixtures is invented;
 * this repository is public.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProjectEditApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final String UNIT = "ds.transactions.idempotency";

  private static final String FILE = """
      { "version": 1,
        "projects": [
          { "key": "ledger", "name": "Example ledger", "summary": "Built the posting service.",
            "questions": [
              { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through the ledger." },
              { "key": "ledger-hot", "rung": "scale", "prompt": "What fails first at 10x?" } ] } ] }
      """;

  private static final String FILE_WITHOUT_HOT = """
      { "version": 1,
        "projects": [
          { "key": "ledger", "name": "Example ledger", "summary": "Built the posting service.",
            "questions": [
              { "key": "ledger-walk", "rung": "walkthrough", "prompt": "Walk me through the ledger." } ] } ] }
      """;

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

  private ResultActions send(MockHttpServletRequestBuilder request, String login, String body) throws Exception {
    request = request.header("Tailscale-User-Login", login).with(RealCsrf.token(mvc, login));
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(request);
  }

  private ResultActions read(String url, String login) throws Exception {
    return mvc.perform(get(url).header("Tailscale-User-Login", login));
  }

  private long newProject(String login, String name) throws Exception {
    String body = send(post("/api/projects"), login, "{\"name\": \"" + name + "\", \"summary\": \"What I did.\"}")
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
  }

  private long newQuestion(String login, long project, String prompt) throws Exception {
    String body = send(post("/api/projects/" + project + "/questions"), login,
        "{\"rung\": \"why\", \"prompt\": \"" + prompt + "\"}")
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
  }

  private long questionId(String key) {
    return jdbc.queryForObject("select id from project_question where key = ?", Long.class, key);
  }

  @Test
  void aProjectAndItsQuestionsCanBeAddedWithoutAFile() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    send(post("/api/projects/" + project + "/questions"), TESTER,
        "{\"rung\": \"failure\", \"prompt\": \"What happens when the gateway dies?\","
            + " \"probes\": \"And the retries?\", \"strongAnswer\": \"Timeouts, failover.\","
            + " \"units\": [\"" + UNIT + "\", \"no.such.unit\", \"" + UNIT + "\"], \"minutes\": 20}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unknownUnits[0]").value("no.such.unit"));

    read("/api/projects/" + project, TESTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Example gateway"))
        .andExpect(jsonPath("$.questions[0].rung").value("failure"))
        .andExpect(jsonPath("$.questions[0].minutes").value(20))
        .andExpect(jsonPath("$.questions[0].units.length()").value(1))
        .andExpect(jsonPath("$.questions[0].units[0].title").value("Idempotency keys"));
    // Keys are made up so the export and a re-import of it match the rows like any imported ones.
    assertThat(jdbc.queryForObject("select key from project_question", String.class)).matches("app-[0-9a-f]{12}");
  }

  @Test
  void changingAQuestionKeepsItsAnswerAndRatings() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    long question = newQuestion(TESTER, project, "Why a gateway?");
    send(put("/api/projects/questions/" + question + "/answer"), TESTER, "{\"answer\": \"Fewer clients to change.\"}")
        .andExpect(status().isOk());
    send(post("/api/projects/questions/" + question + "/attempts"), TESTER, "{\"rating\": \"good\"}")
        .andExpect(status().isOk());

    send(put("/api/projects/questions/" + question), TESTER,
        "{\"rung\": \"change\", \"prompt\": \"What would you change about the gateway?\", \"minutes\": 10}")
        .andExpect(status().isOk());

    read("/api/projects/" + project, TESTER)
        .andExpect(jsonPath("$.questions[0].prompt").value("What would you change about the gateway?"))
        .andExpect(jsonPath("$.questions[0].rung").value("change"))
        .andExpect(jsonPath("$.questions[0].answer").value("Fewer clients to change."))
        .andExpect(jsonPath("$.questions[0].progress.attempts").value(1));
  }

  @Test
  void badInputIsRefusedWithEveryProblemListed() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    send(post("/api/projects/" + project + "/questions"), TESTER,
        "{\"rung\": \"sideways\", \"prompt\": \"  \", \"minutes\": 500}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.problems.length()").value(3));
    send(post("/api/projects"), TESTER, "{\"name\": \"\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.problems[0]").value("The name is empty."));
    assertThat(jdbc.queryForObject("select count(*) from project_question", Integer.class)).isZero();
  }

  @Test
  void hidingKeepsTheAnswerAndRestoringBringsItBack() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    long question = newQuestion(TESTER, project, "Why a gateway?");
    send(put("/api/projects/questions/" + question + "/answer"), TESTER, "{\"answer\": \"Kept.\"}");

    send(delete("/api/projects/questions/" + question), TESTER, null).andExpect(status().isNoContent());
    read("/api/projects/" + project, TESTER)
        .andExpect(jsonPath("$.questions.length()").value(0))
        .andExpect(jsonPath("$.hidden[0].prompt").value("Why a gateway?"));
    // A hidden question can't be answered or changed until it is restored.
    send(put("/api/projects/questions/" + question + "/answer"), TESTER, "{\"answer\": \"x\"}")
        .andExpect(status().isNotFound());

    send(post("/api/projects/questions/" + question + "/restore"), TESTER, null).andExpect(status().isNoContent());
    read("/api/projects/" + project, TESTER)
        .andExpect(jsonPath("$.questions[0].answer").value("Kept."))
        .andExpect(jsonPath("$.hidden.length()").value(0));

    send(delete("/api/projects/" + project), TESTER, null).andExpect(status().isNoContent());
    read("/api/projects", TESTER).andExpect(jsonPath("$.length()").value(0));
    read("/api/projects/hidden", TESTER).andExpect(jsonPath("$[0].name").value("Example gateway"));
    send(post("/api/projects/" + project + "/restore"), TESTER, null).andExpect(status().isNoContent());
    read("/api/projects/" + project, TESTER).andExpect(jsonPath("$.questions[0].answer").value("Kept."));
  }

  @Test
  void reorderingTakesExactlyTheShownQuestions() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    long a = newQuestion(TESTER, project, "First?");
    long b = newQuestion(TESTER, project, "Second?");
    long c = newQuestion(TESTER, project, "Third?");

    send(put("/api/projects/" + project + "/order"), TESTER, "{\"questionIds\": [" + c + ", " + a + ", " + b + "]}")
        .andExpect(status().isNoContent());
    read("/api/projects/" + project, TESTER)
        .andExpect(jsonPath("$.questions[0].prompt").value("Third?"))
        .andExpect(jsonPath("$.questions[2].prompt").value("Second?"));

    // A stale page (a question missing, or one that isn't there) changes nothing.
    send(put("/api/projects/" + project + "/order"), TESTER, "{\"questionIds\": [" + a + ", " + b + "]}")
        .andExpect(status().isConflict());
    read("/api/projects/" + project, TESTER).andExpect(jsonPath("$.questions[0].prompt").value("Third?"));
  }

  @Test
  void anImportNeverHidesWhatWasAddedInTheApp() throws Exception {
    send(post("/api/projects/import"), TESTER, FILE).andExpect(status().isOk());
    long ledger = jdbc.queryForObject("select id from experience_project where key = 'ledger'", Long.class);
    newQuestion(TESTER, ledger, "Added in the app?");
    long gateway = newProject(TESTER, "Example gateway");

    // The same file again: it never knew about either addition, and must not hide them.
    send(post("/api/projects/import"), TESTER, FILE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.questionsRetired").value(0))
        .andExpect(jsonPath("$.projectsRetired").value(0));
    read("/api/projects/" + ledger, TESTER).andExpect(jsonPath("$.questions.length()").value(3));
    read("/api/projects/" + gateway, TESTER).andExpect(status().isOk());

    // Imported questions the file drops are still hidden as before.
    send(post("/api/projects/import"), TESTER, FILE_WITHOUT_HOT)
        .andExpect(jsonPath("$.questionsRetired").value(1));
    read("/api/projects/" + ledger, TESTER)
        .andExpect(jsonPath("$.questions.length()").value(2))
        .andExpect(jsonPath("$.hidden[0].prompt").value("What fails first at 10x?"));
  }

  @Test
  void anEditedImportedQuestionIsTheFilesAgainWhenTheFileIsImported() throws Exception {
    send(post("/api/projects/import"), TESTER, FILE);
    long walk = questionId("ledger-walk");
    send(put("/api/projects/questions/" + walk), TESTER, "{\"rung\": \"walkthrough\", \"prompt\": \"Edited.\"}")
        .andExpect(status().isOk());
    // The file is the source for what it holds: importing it puts its wording back, answer untouched.
    send(post("/api/projects/import"), TESTER, FILE).andExpect(jsonPath("$.questionsUpdated").value(1));
    assertThat(jdbc.queryForObject("select prompt from project_question where id = ?", String.class, walk))
        .isEqualTo("Walk me through the ledger.");
  }

  @Test
  void theOtherLearnerGetsNotFoundForEveryEdit() throws Exception {
    long project = newProject(TESTER, "Example gateway");
    long question = newQuestion(TESTER, project, "Why a gateway?");
    String body = "{\"rung\": \"why\", \"prompt\": \"Mine now?\"}";
    send(put("/api/projects/" + project), OTHER, "{\"name\": \"Mine now\"}").andExpect(status().isNotFound());
    send(post("/api/projects/" + project + "/questions"), OTHER, body).andExpect(status().isNotFound());
    send(put("/api/projects/questions/" + question), OTHER, body).andExpect(status().isNotFound());
    send(delete("/api/projects/questions/" + question), OTHER, null).andExpect(status().isNotFound());
    send(delete("/api/projects/" + project), OTHER, null).andExpect(status().isNotFound());
    send(put("/api/projects/" + project + "/order"), OTHER, "{\"questionIds\": [" + question + "]}")
        .andExpect(status().isNotFound());
    read("/api/projects/hidden", OTHER).andExpect(jsonPath("$.length()").value(0));
    read("/api/projects/" + project, TESTER).andExpect(jsonPath("$.name").value("Example gateway"))
        .andExpect(jsonPath("$.questions[0].prompt").value("Why a gateway?"));
  }

  @Test
  void editingNeedsTheCsrfToken() throws Exception {
    mvc.perform(post("/api/projects").header("Tailscale-User-Login", TESTER)
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
        .andExpect(status().isForbidden());
  }
}
