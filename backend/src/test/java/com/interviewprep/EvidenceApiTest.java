package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/** Browsing the shared evidence, and each learner's own drafts exported as evidence files. */
@SpringBootTest
@ActiveProfiles("test")
class EvidenceApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";

  private static final String DEBRIEF = """
      {"kind": "debrief", "body": {"company": "stripe", "level": "Senior", "interview_date": "2026-10",
        "outcome": "no-offer", "private_notes": "The interviewer seemed rushed.",
        "rounds": [
          {"type": "coding", "summary": "Two problems"},
          {"type": "hld", "questions": [{"text": "Design idempotent payment retries",
                                          "topics": ["ds.transactions"]}]}]}}
      """;

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track, round_type"
        + " restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('distributed', 'Distributed', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order)"
        + " values ('ds.transactions', 'distributed', null, 'Transactions', 0)");
    jdbc.update("insert into company (id, name, category) values ('stripe', 'Stripe', 'fintech'),"
        + " ('general', 'General', 'none'), ('wise', 'Wise', 'fintech')");
    jdbc.update("insert into round_type (id, name) values ('coding', 'Coding'), ('hld', 'High-level design')");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.39.1', 'sha256:t', 't') returning id", Long.class);
    evidence("ev-2025-07-stripe-senior-india-taro", "stripe", "2025-07", release);
    evidence("ev-wise-guide", "wise", null, release);
    // Two coding rounds in a row stay two rounds.
    item("ev-2025-07-stripe-senior-india-taro", 0, "coding", "Phone screen", null, 0);
    item("ev-2025-07-stripe-senior-india-taro", 1, "coding", "Onsite", "Rate limiter", 1);
    item("ev-2025-07-stripe-senior-india-taro", 1, "coding", "Onsite", "Idempotent retries", 2);
  }

  private void evidence(String id, String company, String date, long release) {
    jdbc.update("insert into evidence (id, company_id, level, interview_date, source_kind, source_title,"
        + " accessed, tier, state, release_id) values (?, ?, 'senior', ?, 'candidate-report', 'A report',"
        + " current_date, 'secondary', 'accepted', ?)", id, company, date, release);
  }

  private void item(String evidence, int round, String type, String summary, String question, int order) {
    Long id = jdbc.queryForObject("insert into evidence_item (evidence_id, round_index, round_type, summary,"
        + " question, sort_order) values (?, ?, ?, ?, ?, ?) returning id",
        Long.class, evidence, round, type, summary, question, order);
    if (question != null) {
      jdbc.update("insert into evidence_item_topic (item_id, topic_id) values (?, 'ds.transactions')", id);
    }
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String login) {
    return r.header("Tailscale-User-Login", login);
  }

  private ResultActions send(MockHttpServletRequestBuilder r, String login, String body) throws Exception {
    return mvc.perform(as(r, login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private long draft(String login, String body) throws Exception {
    String response = send(post("/api/evidence/drafts"), login, body).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(response).path("id").asLong();
  }

  @Test
  void reportsAreListedNewestFirstWithTheirTopics() throws Exception {
    mvc.perform(as(get("/api/evidence"), TESTER))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("ev-2025-07-stripe-senior-india-taro"))
        .andExpect(jsonPath("$[0].company").value("Stripe"))
        .andExpect(jsonPath("$[0].rounds").value(2))
        .andExpect(jsonPath("$[0].questions").value(2))
        .andExpect(jsonPath("$[0].topics[0]").value("ds.transactions"))
        .andExpect(jsonPath("$[1].id").value("ev-wise-guide"));
  }

  @Test
  void aReportShowsEachRoundWithItsQuestionsAndTopics() throws Exception {
    mvc.perform(as(get("/api/evidence/ev-2025-07-stripe-senior-india-taro"), TESTER))
        .andExpect(jsonPath("$.rounds.length()").value(2))
        .andExpect(jsonPath("$.rounds[0].summary").value("Phone screen"))
        .andExpect(jsonPath("$.rounds[0].questions.length()").value(0))
        .andExpect(jsonPath("$.rounds[1].name").value("Coding"))
        .andExpect(jsonPath("$.rounds[1].questions[1].text").value("Idempotent retries"))
        .andExpect(jsonPath("$.rounds[1].questions[1].topics[0].name").value("Transactions"));
    mvc.perform(as(get("/api/evidence/ev-nope"), TESTER)).andExpect(status().isNotFound());
  }

  @Test
  void theFormsOfferCompaniesButNotGeneralAndTheRoundTypes() throws Exception {
    mvc.perform(as(get("/api/evidence/options"), TESTER))
        .andExpect(jsonPath("$.companies[*].id").value(org.hamcrest.Matchers.contains("stripe", "wise")))
        .andExpect(jsonPath("$.rounds.length()").value(2));
  }

  @Test
  void draftsAreEachLearnersOwn() throws Exception {
    long id = draft(TESTER, DEBRIEF);
    mvc.perform(as(get("/api/evidence/drafts"), TESTER)).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(as(get("/api/evidence/drafts"), OTHER)).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(as(get("/api/evidence/drafts/" + id), OTHER)).andExpect(status().isNotFound());
    mvc.perform(as(get("/api/evidence/drafts/" + id + "/export"), OTHER)).andExpect(status().isNotFound());

    send(put("/api/evidence/drafts/" + id), TESTER, "{\"body\": {\"company\": \"wise\"}}")
        .andExpect(jsonPath("$.body.company").value("wise"));
    mvc.perform(as(delete("/api/evidence/drafts/" + id), OTHER).with(RealCsrf.token(mvc, OTHER)));
    assertThat(jdbc.queryForObject("select count(*) from evidence_draft", Integer.class)).isEqualTo(1);
    mvc.perform(as(delete("/api/evidence/drafts/" + id), TESTER).with(RealCsrf.token(mvc, TESTER)));
    assertThat(jdbc.queryForObject("select count(*) from evidence_draft", Integer.class)).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aDebriefExportsAsAFirstHandEvidenceFileWithoutPrivateNotes() throws Exception {
    long id = draft(TESTER, DEBRIEF);
    String response = mvc.perform(as(get("/api/evidence/drafts/" + id + "/export"), TESTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.path").value("evidence/2026/ev-2026-10-stripe-senior-debrief.yaml"))
        .andReturn().getResponse().getContentAsString();
    String yaml = json.readTree(response).path("yaml").asString();
    Map<String, Object> record = new Yaml().load(yaml);

    assertThat(record).containsEntry("id", "ev-2026-10-stripe-senior-debrief")
        .containsEntry("company", "stripe").containsEntry("tier", "primary")
        .containsEntry("state", "proposed").containsEntry("outcome", "no-offer")
        .containsEntry("interview_date", "2026-10");
    assertThat(((Map<String, Object>) record.get("source"))).containsEntry("kind", "first-hand");
    List<Map<String, Object>> rounds = (List<Map<String, Object>>) record.get("rounds");
    assertThat(rounds).hasSize(2);
    assertThat(rounds.get(0)).doesNotContainKey("questions");
    // Nothing personal leaves: no private notes, no learner.
    assertThat(yaml).doesNotContain("rushed").doesNotContain("private").doesNotContain("tester");
  }

  @Test
  void anIncompleteDraftSaysEverythingMissingAtOnce() throws Exception {
    long id = draft(TESTER, "{\"kind\": \"debrief\", \"body\": {\"interview_date\": \"Oct\", \"rounds\":"
        + " [{\"type\": \"nope\", \"questions\": [{\"text\": \"\", \"topics\": [\"no.such\"]}]}]}}");
    String message = mvc.perform(as(get("/api/evidence/drafts/" + id + "/export"), TESTER))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.problems.length()").value(5))
        .andReturn().getResponse().getContentAsString();
    assertThat(message).contains("Choose the company.", "must look like 2026", "Round 1: choose its type.",
        "write the question", "unknown topic no.such");
  }

  @Test
  void withoutATokenSendingSaysItIsNotSetUp() throws Exception {
    send(post("/api/evidence/drafts/" + draft(TESTER, DEBRIEF) + "/send"), TESTER, "")
        .andExpect(status().isServiceUnavailable());
    send(post("/api/inbox/articles"), TESTER, "{\"title\": \"T\", \"text\": \"x\"}")
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void savingADraftNeedsTheCsrfToken() throws Exception {
    mvc.perform(as(post("/api/evidence/drafts"), TESTER).contentType(MediaType.APPLICATION_JSON).content(DEBRIEF))
        .andExpect(status().isForbidden());
  }
}
