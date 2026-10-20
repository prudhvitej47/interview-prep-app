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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * Drafts, Run and Submit through the real security chain, against a stub runner that records what it is
 * sent and answers what a test sets.
 */
@SpringBootTest
@ActiveProfiles("test")
class PracticeApiTest extends PostgresTestBase {

  static final String TESTER = "tester@example.com";
  static final String OTHER = "other@example.com";
  static final String CODING = "dsa.window.max-sum";
  static final String CONCEPT = "dsa.window.concept";
  static final String PRIVATE = "dsa.window.tester-only";

  static final HttpServer STUB;
  static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
  static final AtomicReference<String> ANSWER = new AtomicReference<>();
  static final AtomicInteger ANSWER_STATUS = new AtomicInteger(200);
  static final AtomicInteger CALLS = new AtomicInteger();

  static {
    try {
      STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    STUB.createContext("/runs", exchange -> {
      CALLS.incrementAndGet();
      LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = ANSWER.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(ANSWER_STATUS.get(), body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    STUB.start();
  }

  @DynamicPropertySource
  static void runnerUrl(DynamicPropertyRegistry registry) {
    registry.add("app.runner.url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
  }

  @AfterAll
  static void stopStub() {
    STUB.stop(0);
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('dsa', 'DSA', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values ('dsa.window', 'dsa', null, 'Windows', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.43.1', 'sha256:t', 't') returning id", Long.class);
    unit(CODING, "coding", "shared", release);
    unit(CONCEPT, "concept", "shared", release);
    unit(PRIVATE, "coding", "learner:tester", release);
    for (String id : new String[] {CODING, PRIVATE}) {
      jdbc.update("insert into coding_fixture (unit_id, signature, compare) values (?, ?::jsonb, 'exact')", id,
          "{\"method\": \"maxSum\", \"params\": [{\"name\": \"nums\", \"type\": \"int[]\"}, {\"name\": \"k\", \"type\": \"int\"}], \"returns\": \"int\"}");
      testCase(id, "the example", "nums = [2, 1], k = 1", "2", "{\"nums\": [2, 1], \"k\": 1}", "2", false, 0);
      testCase(id, "negatives", "nums = [-3, -1], k = 1", "-1", "{\"nums\": [-3, -1], \"k\": 1}", "-1", false, 1);
      testCase(id, "secret edge", "nums = [9090, 5], k = 1", "9090", "{\"nums\": [9090, 5], \"k\": 1}", "9090", true, 2);
    }
    ANSWER_STATUS.set(200);
    CALLS.set(0);
    LAST_REQUEST.set(null);
  }

  void unit(String id, String type, String visibility, long release) {
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds, origin, visibility,"
        + " state, version, body, content_hash, release_id) values (?, 'dsa.window', ?, ?, 2, 30, '{java-backend}',"
        + " 'synthesized', ?, 'draft', 1, jsonb_build_object('markdown', '## Problem\nSum.'::text), 'h', ?)",
        id, type, "Title of " + id, visibility, release);
  }

  void testCase(String unit, String name, String input, String expected, String args, String expectedValue,
      boolean hidden, int order) {
    jdbc.update("insert into test_case (unit_id, name, input, expected, hidden, sort_order, args, expected_value)"
        + " values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)", unit, name, input, expected, hidden, order, args, expectedValue);
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  ResultActions saveCode(String unit, String code, String login) throws Exception {
    return mvc.perform(as(put("/api/units/" + unit + "/code"), login).with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": " + quote(code) + "}"));
  }

  static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  @Test
  void aDraftIsSavedPerLearnerAndUnitAndHasALimit() throws Exception {
    mvc.perform(as(get("/api/units/" + CODING + "/code"), TESTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(""))
        .andExpect(jsonPath("$.lastSubmit").isEmpty());
    saveCode(CODING, "class Solution {}", TESTER).andExpect(status().isOk()).andExpect(jsonPath("$.savedAt").isNotEmpty());
    mvc.perform(as(get("/api/units/" + CODING + "/code"), TESTER)).andExpect(jsonPath("$.code").value("class Solution {}"));
    mvc.perform(as(get("/api/units/" + CODING + "/code"), OTHER)).andExpect(jsonPath("$.code").value(""));
    saveCode(CODING, "x".repeat(32 * 1024 + 1), TESTER).andExpect(status().isBadRequest());
    mvc.perform(as(get("/api/units/" + PRIVATE + "/code"), OTHER)).andExpect(status().isNotFound());
  }
}
