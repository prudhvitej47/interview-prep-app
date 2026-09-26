package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Send to the curriculum", against a stand-in for GitHub's API that records what the app creates:
 * the branches, and every file with its content, exactly as they would land in the content repo.
 */
@SpringBootTest
@ActiveProfiles("test")
class SendToCurriculumTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Branches created, and files put, in order; taken names answer 422 as GitHub does. */
  static final List<String> branches = new ArrayList<>();
  static final Map<String, String> files = new LinkedHashMap<>();
  static final Set<String> taken = new HashSet<>();
  static final List<String> authorizations = new ArrayList<>();
  /** Slows GitHub's first answer, so two sends can overlap; and makes branch creation fail. */
  static volatile long mainRefDelayMillis;
  static volatile boolean refuseBranches;
  static final HttpServer github = start();

  private static HttpServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        String path = exchange.getRequestURI().getPath();
        JsonNode body = exchange.getRequestMethod().equals("GET") ? null
            : JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int status;
        String response = "{}";
        if (path.endsWith("/git/ref/heads/main")) {
          try {
            Thread.sleep(mainRefDelayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          status = 200;
          response = "{\"object\": {\"sha\": \"base-sha\"}}";
        } else if (path.endsWith("/git/refs") && refuseBranches) {
          status = 500;
        } else if (path.endsWith("/git/refs")) {
          String ref = body.path("ref").asString().substring("refs/heads/".length());
          status = taken.add(ref) ? 201 : 422;
          if (status == 201) {
            branches.add(ref);
          }
        } else if (path.contains("/contents/")) {
          files.put(body.path("branch").asString() + ":" + path.substring(path.indexOf("/contents/") + 10),
              new String(Base64.getDecoder().decode(body.path("content").asString()), StandardCharsets.UTF_8));
          status = 201;
        } else {
          status = 404;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void githubStandIn(DynamicPropertyRegistry registry) {
    registry.add("app.content.github-token", () -> "test-token");
    registry.add("app.content.repo", () -> "owner/content");
    registry.add("app.content.github-api", () -> "http://127.0.0.1:" + github.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    github.stop(0);
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;
  private final String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    branches.clear();
    files.clear();
    taken.clear();
    authorizations.clear();
    mainRefDelayMillis = 0;
    refuseBranches = false;
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track, round_type"
        + " restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('distributed', 'Distributed', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order)"
        + " values ('ds.transactions', 'distributed', null, 'Transactions', 0)");
    jdbc.update("insert into company (id, name, category) values ('stripe', 'Stripe', 'fintech')");
    jdbc.update("insert into round_type (id, name) values ('hld', 'High-level design')");
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String login) {
    return r.header("Tailscale-User-Login", login);
  }

  private ResultActions send(MockHttpServletRequestBuilder r, String login, String body) throws Exception {
    return mvc.perform(as(r, login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private long debrief() throws Exception {
    String response = send(post("/api/evidence/drafts"), TESTER, """
        {"kind": "debrief", "body": {"company": "stripe", "level": "Senior", "interview_date": "2026-10",
          "private_notes": "Nervous about the second round.",
          "rounds": [{"type": "hld", "questions": [{"text": "Design idempotent retries",
                                                    "topics": ["ds.transactions"]}]}]}}
        """).andReturn().getResponse().getContentAsString();
    return JSON.readTree(response).path("id").asLong();
  }

  @Test
  void aDebriefGoesToAProposalBranchWithItsEvidenceAndASummary() throws Exception {
    long id = debrief();
    String branch = "proposals/" + today + "-2026-10-stripe-senior-debrief";
    send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branch").value(branch))
        .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://github.com/owner/content/pulls")));

    assertThat(branches).containsExactly(branch);
    assertThat(authorizations).allMatch("Bearer test-token"::equals);
    String evidence = files.get(branch + ":evidence/2026/ev-2026-10-stripe-senior-debrief.yaml");
    assertThat(evidence).contains("id: ev-2026-10-stripe-senior-debrief", "kind: first-hand", "state: proposed")
        .doesNotContain("Nervous").doesNotContain("tester");
    // The summary the content repo's workflow turns into the pull request's description.
    String summary = files.get(branch + ":changes/" + today + "-2026-10-stripe-senior-debrief.md");
    assertThat(summary).startsWith("# A first-hand debrief: Stripe")
        .contains("`evidence/2026/ev-2026-10-stripe-senior-debrief.yaml`", "ds.transactions", "## Suggested placement");

    // Sent is final: edits belong in the pull request now.
    mvc.perform(as(get("/api/evidence/drafts/" + id), TESTER))
        .andExpect(jsonPath("$.sentBranch").value(branch))
        .andExpect(jsonPath("$.sentAt").isNotEmpty());
    send(put("/api/evidence/drafts/" + id), TESTER, "{\"body\": {}}").andExpect(status().isConflict());
    send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "").andExpect(status().isConflict());
  }

  @Test
  void twoSendsAtOnceMakeOneProposal() throws Exception {
    long id = debrief();
    // The first send is still waiting on GitHub when the second arrives, as with a double click.
    mainRefDelayMillis = 500;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "")
          .andReturn().getResponse().getStatus());
      var second = pool.submit(() -> send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "")
          .andReturn().getResponse().getStatus());
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdown();
    }
    assertThat(branches).hasSize(1);
  }

  @Test
  void aSendGitHubRefusedCanBeTriedAgain() throws Exception {
    long id = debrief();
    refuseBranches = true;
    send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "").andExpect(status().isBadGateway());
    mvc.perform(as(get("/api/evidence/drafts/" + id), TESTER)).andExpect(jsonPath("$.sentAt").isEmpty());

    refuseBranches = false;
    send(post("/api/evidence/drafts/" + id + "/send"), TESTER, "").andExpect(status().isOk());
    assertThat(branches).hasSize(1);
  }

  @Test
  void aTakenBranchNameGetsANumber() throws Exception {
    taken.add("proposals/" + today + "-2026-10-stripe-senior-debrief");
    send(post("/api/evidence/drafts/" + debrief() + "/send"), TESTER, "")
        .andExpect(jsonPath("$.branch").value("proposals/" + today + "-2026-10-stripe-senior-debrief-2"));
  }

  @Test
  void anIncompleteDebriefIsNotSent() throws Exception {
    String response = send(post("/api/evidence/drafts"), TESTER, "{\"kind\": \"debrief\", \"body\": {}}")
        .andReturn().getResponse().getContentAsString();
    send(post("/api/evidence/drafts/" + JSON.readTree(response).path("id").asLong() + "/send"), TESTER, "")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.problems[0]").value("Choose the company."));
    assertThat(branches).isEmpty();
  }

  @Test
  void onlyTheOwnerCanSendADraft() throws Exception {
    send(post("/api/evidence/drafts/" + debrief() + "/send"), OTHER, "").andExpect(status().isNotFound());
    assertThat(branches).isEmpty();
  }

  @Test
  void anArticleGoesToAnInboxBranchAsRawMaterial() throws Exception {
    send(post("/api/inbox/articles"), TESTER, """
        {"title": "My Stripe loop: 5 rounds, one surprise!", "url": "https://medium.com/@someone/stripe-loop",
         "company": "stripe", "text": "Round 1 was a bug bash..."}
        """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branch").value("inbox/" + today + "-stripe-my-stripe-loop-5-rounds-one-surprise"));
    String file = files.get("inbox/" + today + "-stripe-my-stripe-loop-5-rounds-one-surprise:inbox/"
        + today + "-stripe-my-stripe-loop-5-rounds-one-surprise.md");
    assertThat(file).startsWith("# My Stripe loop: 5 rounds, one surprise!\n")
        .contains("- Source: https://medium.com/@someone/stripe-loop", "- Copied: " + today,
            "- Company: stripe", "Round 1 was a bug bash...");
  }

  @Test
  void anArticleNeedsATitleAndTextOrALink() throws Exception {
    send(post("/api/inbox/articles"), TESTER, "{\"url\": \"ftp://x\", \"company\": \"nope\"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.problems.length()").value(3));
    send(post("/api/inbox/articles"), TESTER, "{\"title\": \"Notes\"}")
        .andExpect(jsonPath("$.problems[0]").value("Paste the text, or at least give the link."));
    assertThat(branches).isEmpty();
  }
}
