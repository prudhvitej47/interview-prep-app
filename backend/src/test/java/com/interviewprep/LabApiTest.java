package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
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
 * Laptop labs through the real security chain: the starter download, the token check and "Lab done".
 * The fixture's lab stores the salted hash of token "0000002a".
 */
@SpringBootTest
@ActiveProfiles("test")
class LabApiTest extends PostgresTestBase {

  private static final String TESTER = "tester@example.com";
  private static final String OTHER = "other@example.com";
  private static final String LAB_UNIT = "spring.data.lab-unit";
  private static final String PLAIN_UNIT = "spring.data.plain-unit";
  private static final String PRIVATE_LAB = "spring.data.tester-lab";
  private static final String HASH = "sha256:471b61a050e0644c068f523480278c9b076a427e378bfb5952cccd784d63bb38";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc.execute("truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
    jdbc.update("insert into domain (id, name, weight, sort_order) values ('spring', 'Spring', 100, 0)");
    jdbc.update("insert into topic (id, domain_id, parent_id, name, sort_order) values"
        + " ('spring.data', 'spring', null, 'Spring Data', 0)");
    long release = jdbc.queryForObject("insert into curriculum_release (version, bundle_digest, git_sha)"
        + " values ('2026.42.1', 'sha256:t', 't') returning id", Long.class);
    unit(LAB_UNIT, "shared", release);
    unit(PLAIN_UNIT, "shared", release);
    unit(PRIVATE_LAB, "learner:tester", release);
    lab(LAB_UNIT, "[{\"name\": \"LabCheck.java\", \"content\": \"class LabCheck {}\"},"
        + " {\"name\": \"PaymentQueries.java\", \"content\": \"interface Payments {}\"},"
        + " {\"name\": \"../evil.java\", \"content\": \"x\"}, {\"name\": \".hidden\", \"content\": \"x\"}]");
    lab(PRIVATE_LAB, "[{\"name\": \"LabCheck.java\", \"content\": \"class LabCheck {}\"}]");
  }

  private void unit(String id, String visibility, long release) {
    jdbc.update("insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds,"
        + " origin, visibility, state, version, body, content_hash, release_id)"
        + " values (?, 'spring.data', 'concept', ?, 3, 40, '{java-backend}', 'synthesized', ?, 'draft', 1,"
        + " jsonb_build_object('markdown', '## Why it matters\nQueries.'::text), 'h', ?)",
        id, "Title of " + id, visibility, release);
  }

  private void lab(String unitId, String files) {
    jdbc.update("insert into lab (unit_id, title, minutes, needs, readme, files, salt, token_hash)"
        + " values (?, 'Count the queries', 20, '{jdk,jbang}', 'Steps.', ?::jsonb, '0123456789abcdef', ?)",
        unitId, files, HASH);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String login) {
    return request.header("Tailscale-User-Login", login);
  }

  private ResultActions check(String unitId, String token, String login) throws Exception {
    return mvc.perform(as(post("/api/units/" + unitId + "/lab/check"), login)
        .with(RealCsrf.token(mvc, login))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\": \"" + token.replace("\n", "\\n") + "\"}"));
  }

  @Test
  void theStarterZipHoldsTheReadmeAndOnlySafeFileNames() throws Exception {
    byte[] zip = mvc.perform(as(get("/api/units/" + LAB_UNIT + "/lab/starter.zip"), TESTER))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray();
    List<String> names = new ArrayList<>();
    String readme = null;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        names.add(e.getName());
        if (e.getName().endsWith("README.md")) {
          readme = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
      }
    }
    assertThat(names).containsExactly(LAB_UNIT + "/README.md", LAB_UNIT + "/LabCheck.java",
        LAB_UNIT + "/PaymentQueries.java");
    assertThat(readme).isEqualTo("# Lab: Count the queries\n\nSteps.");
  }

  @Test
  void theUnitPageShowsTheLabButNeverItsSecret() throws Exception {
    String body = mvc.perform(as(get("/api/units/" + LAB_UNIT), TESTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lab.title").value("Count the queries"))
        .andExpect(jsonPath("$.lab.minutes").value(20))
        .andExpect(jsonPath("$.lab.needs[1]").value("jbang"))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("0123456789abcdef").doesNotContain("token_hash").doesNotContain("salt");
    mvc.perform(as(get("/api/units/" + PLAIN_UNIT), TESTER)).andExpect(jsonPath("$.lab").isEmpty());
  }

  @Test
  void theRightTokenMarksTheLabDoneOnceInWhateverShapeItIsPasted() throws Exception {
    mvc.perform(as(get("/api/units/" + LAB_UNIT + "/lab"), TESTER)).andExpect(jsonPath("$.doneAt").isEmpty());
    String first = check(LAB_UNIT, "TOKEN: 0000002A\n", TESTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.right").value(true))
        .andReturn().getResponse().getContentAsString();
    String again = check(LAB_UNIT, " 0000002a ", TESTER)
        .andExpect(jsonPath("$.right").value(true))
        .andReturn().getResponse().getContentAsString();
    assertThat(again).isEqualTo(first);
    mvc.perform(as(get("/api/units/" + LAB_UNIT + "/lab"), TESTER)).andExpect(jsonPath("$.doneAt").isNotEmpty());
    // A lab is not a rating: nothing is written to attempts.
    assertThat(jdbc.queryForObject("select count(*) from attempt", Integer.class)).isZero();
  }

  @Test
  void aWrongTokenIsNotDoneAndAMalformedOneIsRefused() throws Exception {
    check(LAB_UNIT, "deadbeef", TESTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.right").value(false))
        .andExpect(jsonPath("$.doneAt").isEmpty());
    check(LAB_UNIT, "0000002", TESTER).andExpect(status().isBadRequest());
    check(LAB_UNIT, "hello", TESTER).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from lab_result", Integer.class)).isZero();
  }

  @Test
  void noLabOrSomeoneElsesPrivateUnitIsNotFoundEverywhere() throws Exception {
    for (String[] c : new String[][] {{PLAIN_UNIT, TESTER}, {PRIVATE_LAB, OTHER}, {"no.such.unit", TESTER}}) {
      mvc.perform(as(get("/api/units/" + c[0] + "/lab/starter.zip"), c[1])).andExpect(status().isNotFound());
      mvc.perform(as(get("/api/units/" + c[0] + "/lab"), c[1])).andExpect(status().isNotFound());
      check(c[0], "0000002a", c[1]).andExpect(status().isNotFound());
    }
    mvc.perform(as(get("/api/units/" + PRIVATE_LAB + "/lab/starter.zip"), TESTER)).andExpect(status().isOk());
  }
}
