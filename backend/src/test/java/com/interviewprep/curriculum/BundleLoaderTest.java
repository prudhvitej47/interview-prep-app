package com.interviewprep.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewprep.PostgresTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The loader against a real PostgreSQL, with a small hand-written bundle rather than the real
 * curriculum: the content repository is private, and a fixture makes each property visible.
 *
 * <p>What matters is that content can change underneath learners without their history breaking:
 * units keep their ids, get versioned only when they really change, and are retired, never deleted.
 */
@SpringBootTest
@ActiveProfiles("test")
class BundleLoaderTest extends PostgresTestBase {

  private static final String A = "ds.transactions.idempotency";
  private static final String B = "ds.transactions.sagas.intro";

  @Autowired private BundleLoader loader;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper json;

  @TempDir Path dir;

  @BeforeEach
  void emptyDatabase() {
    // The test database is shared across classes, so start every test from nothing.
    jdbc.execute(
        "truncate curriculum_release, domain, company, learner, source, track restart identity cascade");
  }

  @Test
  void theFirstLoadCreatesAReleaseWithEverythingInIt() {
    BundleLoader.Result result = load(base());

    assertThat(result.outcome()).isEqualTo(BundleLoader.Outcome.RELEASED);
    assertThat(result.version()).matches("\\d{4}\\.\\d{2}\\.1");
    assertThat(result.added()).isEqualTo(2);

    assertThat(count("domain")).isEqualTo(2);
    assertThat(count("topic")).isEqualTo(3);
    assertThat(jdbc.queryForObject("select parent_id from topic where id = 'ds.transactions.sagas'",
        String.class)).isEqualTo("ds.transactions");

    assertThat(count("unit")).isEqualTo(2);
    assertThat(jdbc.queryForObject("select prereq_id from unit_prereq where unit_id = ?",
        String.class, B)).isEqualTo(A);
    assertThat(jdbc.queryForObject("select rounds[1] from unit where id = ?", String.class, A))
        .isEqualTo("hld");

    // Both units cite the same book chapter: one source row, two links to it.
    assertThat(count("source")).isEqualTo(1);
    assertThat(count("unit_source")).isEqualTo(2);

    assertThat(count("track_unit")).isEqualTo(1);
  }

  @Test
  void evidenceArrivesWithTheRelease() {
    load(base());

    assertThat(count("evidence")).isEqualTo(1);
    // One question in the HLD round, and a behavioural round with a summary and no questions.
    assertThat(count("evidence_item")).isEqualTo(2);
    assertThat(jdbc.queryForList(
            "select topic_id from evidence_item_topic order by topic_id", String.class))
        .containsExactly("ds.transactions", "hld.payments");
    assertThat(jdbc.queryForObject("select aliases[1] from company where id = 'stripe'",
        String.class)).isEqualTo("stripe-inc");
  }

  @Test
  void loadingTheSameBundleAgainChangesNothing() {
    load(base());
    BundleLoader.Result again = load(base());

    assertThat(again.outcome()).isEqualTo(BundleLoader.Outcome.UNCHANGED);
    assertThat(count("curriculum_release")).isEqualTo(1);
  }

  @Test
  void aChangedUnitIsVersionedAndLearnerHistoryStillPointsAtIt() {
    load(base());
    long learner = jdbc.queryForObject(
        "insert into learner (slug, display_name) values ('t', 'T') returning id", Long.class);
    long firstRelease = jdbc.queryForObject("select id from curriculum_release", Long.class);
    jdbc.update("insert into placement (learner_id, unit_id, release_id, choice)"
        + " values (?, ?, ?, 'next-week')", learner, A, firstRelease);

    BundleLoader.Result result = load(withUnitEdited(base(), A));

    assertThat(result.changed()).isEqualTo(1);
    assertThat(result.version()).endsWith(".2");
    assertThat(version(A)).isEqualTo(2);
    // The untouched unit keeps its version and the release it last changed in.
    assertThat(version(B)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select release_id from unit where id = ?", Long.class, B))
        .isEqualTo(firstRelease);
    // The whole point: the learner's row survives the content changing underneath it.
    assertThat(jdbc.queryForObject("select count(*) from placement where unit_id = ?",
        Integer.class, A)).isEqualTo(1);
  }

  @Test
  void aUnitRemovedFromTheBundleIsRetiredNotDeleted() {
    load(base());
    BundleLoader.Result result = load(withUnitRemoved(base(), B));

    assertThat(result.retired()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select state from unit where id = ?", String.class, B))
        .isEqualTo("retired");
    assertThat(count("unit")).isEqualTo(2);
  }

  @Test
  void revertingToEarlierContentIsANewRelease() {
    load(base());
    load(withUnitEdited(base(), A));
    BundleLoader.Result reverted = load(base());

    assertThat(reverted.outcome()).isEqualTo(BundleLoader.Outcome.RELEASED);
    assertThat(count("curriculum_release")).isEqualTo(3);
    assertThat(version(A)).isEqualTo(3);
  }

  @Test
  void aFailureAnywhereRollsTheWholeReleaseBack() {
    // Evidence naming a company that does not exist fails in the evidence module's listener.
    // Because it runs inside the loader's transaction, no half-loaded release is left behind.
    ObjectNode content = base();
    ((ObjectNode) content.path("evidence").get(0)).put("company", "no-such-company");

    // Asserting the specific constraint proves the failure came from the evidence listener,
    // not from somewhere earlier that would make this test pass for the wrong reason.
    assertThatThrownBy(() -> load(content)).hasMessageContaining("evidence_company_id_fkey");
    assertThat(count("curriculum_release")).isZero();
    assertThat(count("unit")).isZero();
  }

  @Test
  void anUnsupportedBundleSchemaIsRefusedWithoutWritingAnything() {
    ObjectNode content = base();
    content.put("schema_version", 99);

    assertThatThrownBy(() -> load(content)).hasMessageContaining("schema 99");
    assertThat(count("curriculum_release")).isZero();
  }

  @Test
  void noBundleIsNotAnError() {
    assertThat(loader.load(dir.resolve("missing")).outcome())
        .isEqualTo(BundleLoader.Outcome.NO_BUNDLE);
  }

  // ---------------------------------------------------------------------------

  private BundleLoader.Result load(ObjectNode content) {
    try {
      String body = json.writeValueAsString(content);
      Files.writeString(dir.resolve("content.json"), body);
      // The loader only compares hashes, so any value that tracks the content will do.
      ObjectNode manifest = json.createObjectNode()
          .put("content_hash", "sha256:" + Integer.toHexString(body.hashCode()))
          .put("git_sha", "test");
      Files.writeString(dir.resolve("manifest.json"), json.writeValueAsString(manifest));
      return loader.load(dir);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int version(String unit) {
    return jdbc.queryForObject("select version from unit where id = ?", Integer.class, unit);
  }

  private ObjectNode withUnitEdited(ObjectNode content, String unitId) {
    for (JsonNode u : content.path("units")) {
      if (u.path("id").asString().equals(unitId)) {
        ((ObjectNode) u).put("content_hash", u.path("content_hash").asString() + "-edited");
        ((ObjectNode) u.path("body")).put("markdown", "## Why it matters\nRewritten.");
      }
    }
    return content;
  }

  private ObjectNode withUnitRemoved(ObjectNode content, String unitId) {
    ArrayNode units = (ArrayNode) content.path("units");
    for (int i = units.size() - 1; i >= 0; i--) {
      if (units.get(i).path("id").asString().equals(unitId)) {
        units.remove(i);
      }
    }
    return content;
  }

  private ObjectNode base() {
    return (ObjectNode) json.readTree("""
        {
          "schema_version": 1,
          "domains": [
            {"id": "distributed", "name": "Distributed systems", "weight": 60, "sort_order": 0,
             "topics": [
               {"id": "ds.transactions", "name": "Transactions", "parent_id": null, "sort_order": 0},
               {"id": "ds.transactions.sagas", "name": "Sagas", "parent_id": "ds.transactions",
                "sort_order": 1}]},
            {"id": "hld", "name": "High-level design", "weight": 40, "sort_order": 1,
             "topics": [{"id": "hld.payments", "name": "Payments", "parent_id": null, "sort_order": 0}]}
          ],
          "companies": [
            {"id": "general", "name": "General", "category": "none"},
            {"id": "stripe", "name": "Stripe", "category": "fintech", "aliases": ["stripe-inc"]}
          ],
          "rounds": [{"id": "hld", "name": "High-level design"}],
          "units": [
            {"id": "ds.transactions.idempotency", "topic": "ds.transactions", "type": "concept",
             "title": "Idempotency keys", "difficulty": 3, "est_minutes": 30, "rounds": ["hld"],
             "technologies": ["postgresql"], "origin": "synthesized", "visibility": "shared",
             "state": "draft", "version": 1, "prerequisites": [],
             "sources": [{"kind": "book", "title": "System Design Interview Vol. 2", "locator": "Ch. 11"}],
             "body": {"markdown": "## Why it matters\\nRetries."}, "tests": null,
             "content_hash": "sha256:a"},
            {"id": "ds.transactions.sagas.intro", "topic": "ds.transactions.sagas", "type": "concept",
             "title": "Sagas", "difficulty": 3, "est_minutes": 30, "rounds": ["hld"],
             "technologies": [], "origin": "synthesized", "state": "draft", "version": 1,
             "prerequisites": ["ds.transactions.idempotency"],
             "sources": [{"kind": "book", "title": "System Design Interview Vol. 2", "locator": "Ch. 11"}],
             "body": {"markdown": "## Why it matters\\nCompensation."}, "tests": null,
             "content_hash": "sha256:b"}
          ],
          "evidence": [
            {"id": "ev-2025-07-stripe-test", "company": "stripe", "tier": "secondary",
             "state": "accepted",
             "source": {"kind": "candidate-report", "title": "Stripe loop", "accessed": "2026-09-18"},
             "rounds": [
               {"type": "hld", "summary": "Payments design",
                "questions": [{"text": "Design a payment system",
                               "topics": ["hld.payments", "ds.transactions"]}]},
               {"type": "behavioral", "summary": "Culture and ownership"}]}
          ],
          "tracks": [
            {"id": "payments", "title": "Payments",
             "modules": [{"id": "idempotency", "title": "Idempotency",
                          "topics": ["ds.transactions"], "units": ["ds.transactions.idempotency"]}]}
          ]
        }
        """);
  }
}
