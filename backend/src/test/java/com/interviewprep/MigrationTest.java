package com.interviewprep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs every migration against a real Postgres and checks the constraints that carry a decision,
 * rather than re-listing the DDL. A migration that applies cleanly but drops one of these is a
 * migration that has quietly changed the design.
 */
@SpringBootTest
@ActiveProfiles("test")
class MigrationTest extends PostgresTestBase {

  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  @Test
  void everyMigrationApplied() {
    Integer failed =
        jdbc().queryForObject(
            "select count(*) from flyway_schema_history where success = false", Integer.class);
    assertThat(failed).isZero();

    assertThat(jdbc().queryForList("select version from flyway_schema_history order by installed_rank",
            String.class))
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
  }

  @Test
  void coreTablesExist() {
    assertThat(tableNames())
        .contains(
            "learner", "prep_cycle", "learner_competency", "experience_project",
            "domain", "topic", "unit", "unit_prereq", "source", "unit_source", "visual",
            "test_case", "sql_fixture", "track", "track_unit", "curriculum_release",
            "company", "evidence", "evidence_item", "evidence_item_topic",
            "change_proposal", "placement", "note", "attempt", "week_plan", "plan_item", "learner_weight", "planned_break");
  }

  @Test
  void aLearnerCanOnlyHaveOneActiveCycle() {
    Long learnerId = insertLearner("tester-one");
    jdbc().update(
        "insert into prep_cycle (learner_id, started_on, status) values (?, current_date, 'active')",
        learnerId);

    assertThatThrownBy(
            () ->
                jdbc().update(
                    "insert into prep_cycle (learner_id, started_on, status)"
                        + " values (?, current_date, 'active')",
                    learnerId))
        .hasMessageContaining("prep_cycle_one_active_per_learner");

    // A paused one alongside the active one is fine — that is the history.
    jdbc().update(
        "insert into prep_cycle (learner_id, started_on, status) values (?, current_date, 'paused')",
        learnerId);
  }

  @Test
  void competencyIsRatedOncePerScope() {
    Long learnerId = insertLearner("tester-two");
    jdbc().update(
        "insert into learner_competency (learner_id, scope, scope_id, rating, source)"
            + " values (?, 'domain', 'databases', 3, 'self-rating')",
        learnerId);

    // The same scope id under a different scope is a different thing, and allowed.
    jdbc().update(
        "insert into learner_competency (learner_id, scope, scope_id, rating, source)"
            + " values (?, 'topic', 'databases', 4, 'self-rating')",
        learnerId);

    assertThatThrownBy(
            () ->
                jdbc().update(
                    "insert into learner_competency (learner_id, scope, scope_id, rating, source)"
                        + " values (?, 'domain', 'databases', 5, 'self-rating')",
                    learnerId))
        .hasMessageContaining("learner_competency_pkey");
  }

  @Test
  void aBundleCanBeReleasedAgainAfterARevert() {
    // V3 dropped V1's unique constraint: reverting a bad change legitimately returns the
    // curriculum to content it has had before. The loader, not the schema, decides "unchanged".
    jdbc().update(
        "insert into curriculum_release (version, bundle_digest, git_sha)"
            + " values ('2026.38.1', 'sha256:revert-test', 'abc123')");
    jdbc().update(
        "insert into curriculum_release (version, bundle_digest, git_sha)"
            + " values ('2026.38.3', 'sha256:revert-test', 'ghi789')");

    // The version is still unique: two releases can share content, never a number.
    assertThatThrownBy(
            () ->
                jdbc().update(
                    "insert into curriculum_release (version, bundle_digest, git_sha)"
                        + " values ('2026.38.1', 'sha256:other', 'jkl012')"))
        .hasMessageContaining("curriculum_release_version_key");
  }

  @Test
  void unitVisibilityIsSharedOrOneLearner() {
    assertThatThrownBy(() -> insertUnit("bad.visibility", "everyone"))
        .hasMessageContaining("unit_visibility_check");

    insertUnit("ok.shared", "shared");
    insertUnit("ok.private", "learner:tripti");
  }

  private List<String> tableNames() {
    return jdbc().queryForList(
        "select table_name from information_schema.tables where table_schema = 'public'",
        String.class);
  }

  private Long insertLearner(String slug) {
    return jdbc().queryForObject(
        "insert into learner (slug, display_name) values (?, ?) returning id",
        Long.class, slug, slug);
  }

  private void insertUnit(String id, String visibility) {
    jdbc().update(
        "insert into domain (id, name, weight, sort_order) values ('t', 't', 10, 1)"
            + " on conflict do nothing");
    jdbc().update(
        "insert into topic (id, domain_id, name, sort_order) values ('t.topic', 't', 't', 1)"
            + " on conflict do nothing");
    jdbc().update(
        "insert into curriculum_release (version, bundle_digest, git_sha)"
            + " values ('0.0.0', 'sha256:test', 'test') on conflict do nothing");
    Long releaseId =
        jdbc().queryForObject(
            "select id from curriculum_release where bundle_digest = 'sha256:test'", Long.class);
    jdbc().update(
        "insert into unit (id, topic_id, type, title, difficulty, est_minutes, origin,"
            + " visibility, state, version, body, content_hash, release_id)"
            + " values (?, 't.topic', 'concept', 'T', 1, 30, 'synthesized', ?, 'draft', 1,"
            + " '{}'::jsonb, 'h', ?)",
        id, visibility, releaseId);
  }
}
