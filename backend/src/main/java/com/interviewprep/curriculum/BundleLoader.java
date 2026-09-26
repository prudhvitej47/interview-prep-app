package com.interviewprep.curriculum;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the content bundle published by the content repository into a numbered curriculum release.
 *
 * <p>Units are upserted by their permanent id and compared by content hash, so only units a learner
 * could see change are versioned. Units that leave the bundle are retired, never deleted: an attempt
 * or a plan item from months ago still has to point at something.
 *
 * <p>The bundle is already parsed and validated by the content repository, so this deserializes JSON
 * and writes rows. It does not re-check the content.
 */
@Component
class BundleLoader {

  private static final Logger log = LoggerFactory.getLogger(BundleLoader.class);
  private static final int SUPPORTED_SCHEMA = 1;
  // Weeks run Monday to Sunday in India time, the same weeks the planner and the streak use.
  private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Kolkata");

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ApplicationEventPublisher events;
  private final JsonMapper json;
  private final String configuredDir;

  BundleLoader(
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate transaction,
      ApplicationEventPublisher events,
      JsonMapper json,
      @Value("${app.content.bundle-dir:}") String configuredDir) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.events = events;
    this.json = json;
    this.configuredDir = configuredDir;
  }

  record Result(Outcome outcome, String version, int added, int changed, int retired) {
    static Result of(Outcome outcome) {
      return new Result(outcome, null, 0, 0, 0);
    }
  }

  enum Outcome {
    NO_BUNDLE,
    UNCHANGED,
    RELEASED
  }

  @EventListener(ApplicationReadyEvent.class)
  void loadOnStartup() {
    if (configuredDir.isBlank()) {
      log.info("No content bundle configured (app.content.bundle-dir); starting with what is loaded");
      return;
    }
    Result result = load(Path.of(configuredDir));
    switch (result.outcome()) {
      case NO_BUNDLE -> log.warn("No content bundle found in {}", configuredDir);
      case UNCHANGED -> log.info("Content bundle unchanged since the last release");
      case RELEASED ->
          log.info(
              "Loaded curriculum release {}: {} units added, {} changed, {} retired",
              result.version(), result.added(), result.changed(), result.retired());
    }
  }

  Result load(Path dir) {
    Path manifestPath = dir.resolve("manifest.json");
    Path contentPath = dir.resolve("content.json");
    if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(contentPath)) {
      return Result.of(Outcome.NO_BUNDLE);
    }
    JsonNode manifest = json.readTree(manifestPath);
    String contentHash = manifest.path("content_hash").asString();

    return transaction.execute(status -> {
      // Against the latest release only, so reverting to earlier content is still a release.
      String latest = jdbc.query(
          "select bundle_digest from curriculum_release order by id desc limit 1",
          rs -> rs.next() ? rs.getString(1) : null);
      if (contentHash.equals(latest)) {
        return Result.of(Outcome.UNCHANGED);
      }

      JsonNode content = json.readTree(contentPath);
      int schema = content.path("schema_version").asInt();
      if (schema != SUPPORTED_SCHEMA) {
        throw new IllegalStateException(
            "Content bundle schema " + schema + " is not supported (expected " + SUPPORTED_SCHEMA + ")");
      }

      String version = nextVersion();
      long releaseId = insertRelease(version, contentHash, manifest);
      upsertTaxonomy(content);
      int[] counts = upsertUnits(content.path("units"), releaseId);
      replaceTracks(content.path("tracks"));
      jdbc.update(
          "update curriculum_release set units_added = :a, units_changed = :c, units_retired = :r"
              + " where id = :id",
          new MapSqlParameterSource()
              .addValue("a", counts[0]).addValue("c", counts[1]).addValue("r", counts[2])
              .addValue("id", releaseId));

      // Synchronous and inside this transaction: if a listener fails, the release is rolled back.
      events.publishEvent(new CurriculumReleased(releaseId, content));
      return new Result(Outcome.RELEASED, version, counts[0], counts[1], counts[2]);
    });
  }

  /** Year.week.sequence, e.g. 2026.39.2 for the second release in ISO week 39. */
  private String nextVersion() {
    LocalDate today = LocalDate.now(STUDY_ZONE);
    String prefix = "%d.%02d.".formatted(
        today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    Integer thisWeek = jdbc.queryForObject(
        "select count(*) from curriculum_release where version like :p",
        new MapSqlParameterSource("p", prefix + "%"), Integer.class);
    return prefix + (thisWeek + 1);
  }

  private long insertRelease(String version, String contentHash, JsonNode manifest) {
    return jdbc.queryForObject(
        "insert into curriculum_release (version, bundle_digest, git_sha, changelog)"
            + " values (:version, :digest, :sha, :changelog) returning id",
        new MapSqlParameterSource()
            .addValue("version", version)
            .addValue("digest", contentHash)
            .addValue("sha", manifest.path("git_sha").asString())
            .addValue("changelog", textOrNull(manifest.path("changelog"))),
        Long.class);
  }

  private void upsertTaxonomy(JsonNode content) {
    for (JsonNode d : content.path("domains")) {
      jdbc.update(
          "insert into domain (id, name, weight, sort_order) values (:id, :name, :weight, :order)"
              + " on conflict (id) do update set name = excluded.name, weight = excluded.weight,"
              + " sort_order = excluded.sort_order, updated_at = now()"
              + " where (domain.name, domain.weight, domain.sort_order)"
              + " is distinct from (excluded.name, excluded.weight, excluded.sort_order)",
          new MapSqlParameterSource()
              .addValue("id", d.path("id").asString())
              .addValue("name", d.path("name").asString())
              .addValue("weight", d.path("weight").asInt())
              .addValue("order", d.path("sort_order").asInt()));
      // The bundle lists every topic before its subtopics, so a parent always exists by the time
      // a child refers to it.
      for (JsonNode t : d.path("topics")) {
        jdbc.update(
            "insert into topic (id, domain_id, parent_id, name, sort_order)"
                + " values (:id, :domain, :parent, :name, :order)"
                + " on conflict (id) do update set domain_id = excluded.domain_id,"
                + " parent_id = excluded.parent_id, name = excluded.name,"
                + " sort_order = excluded.sort_order, updated_at = now()"
                + " where (topic.domain_id, topic.parent_id, topic.name, topic.sort_order)"
                + " is distinct from"
                + " (excluded.domain_id, excluded.parent_id, excluded.name, excluded.sort_order)",
            new MapSqlParameterSource()
                .addValue("id", t.path("id").asString())
                .addValue("domain", d.path("id").asString())
                .addValue("parent", textOrNull(t.path("parent_id")))
                .addValue("name", t.path("name").asString())
                .addValue("order", t.path("sort_order").asInt()));
      }
    }
  }

  /** Returns {added, changed, retired}. */
  private int[] upsertUnits(JsonNode units, long releaseId) {
    Map<String, String> existing = new HashMap<>();
    Set<String> alreadyRetired = new HashSet<>();
    jdbc.query("select id, content_hash, state from unit", rs -> {
      existing.put(rs.getString("id"), rs.getString("content_hash"));
      if ("retired".equals(rs.getString("state"))) {
        alreadyRetired.add(rs.getString("id"));
      }
    });

    int added = 0;
    int changed = 0;
    List<JsonNode> touched = new ArrayList<>();
    Set<String> inBundle = new HashSet<>();
    for (JsonNode u : units) {
      String id = u.path("id").asString();
      String hash = u.path("content_hash").asString();
      inBundle.add(id);
      // A retired unit coming back has the hash it had before retiring, so it counts as changed:
      // otherwise a reverted removal would stay retired for good.
      if (hash.equals(existing.get(id)) && !alreadyRetired.contains(id)) {
        continue; // unchanged: keeps its version and the release it last changed in
      }
      MapSqlParameterSource p = unitParams(u, releaseId);
      if (existing.containsKey(id)) {
        jdbc.update(
            "update unit set topic_id = :topic, type = :type, title = :title,"
                + " difficulty = :difficulty, est_minutes = :minutes,"
                + " rounds = array(select jsonb_array_elements_text(cast(:rounds as jsonb))),"
                + " technologies = array(select jsonb_array_elements_text(cast(:tech as jsonb))),"
                + " origin = :origin, visibility = :visibility, state = :state,"
                + " version = version + 1, body = cast(:body as jsonb), content_hash = :hash,"
                + " release_id = :release, updated_at = now() where id = :id",
            p);
        changed++;
      } else {
        jdbc.update(
            "insert into unit (id, topic_id, type, title, difficulty, est_minutes, rounds,"
                + " technologies, origin, visibility, state, version, body, content_hash, release_id)"
                + " values (:id, :topic, :type, :title, :difficulty, :minutes,"
                + " array(select jsonb_array_elements_text(cast(:rounds as jsonb))),"
                + " array(select jsonb_array_elements_text(cast(:tech as jsonb))),"
                + " :origin, :visibility, :state, 1, cast(:body as jsonb), :hash, :release)",
            p);
        added++;
      }
      touched.add(u);
    }

    // Links are rewritten after every unit exists, because a prerequisite can point forward.
    for (JsonNode u : touched) {
      replacePrerequisites(u);
      replaceSources(u);
      replaceTests(u);
    }

    // Gone from the bundle: retired, not deleted, so history that points at it stays valid.
    int retired = 0;
    for (String id : existing.keySet()) {
      if (!inBundle.contains(id) && !alreadyRetired.contains(id)) {
        jdbc.update(
            "update unit set state = 'retired', release_id = :release, updated_at = now()"
                + " where id = :id",
            new MapSqlParameterSource().addValue("release", releaseId).addValue("id", id));
        retired++;
      }
    }
    return new int[] {added, changed, retired};
  }

  private MapSqlParameterSource unitParams(JsonNode u, long releaseId) {
    return new MapSqlParameterSource()
        .addValue("id", u.path("id").asString())
        .addValue("topic", u.path("topic").asString())
        .addValue("type", u.path("type").asString())
        .addValue("title", u.path("title").asString())
        .addValue("difficulty", u.path("difficulty").asInt())
        .addValue("minutes", u.path("est_minutes").asInt())
        .addValue("rounds", arrayJson(u.path("rounds")))
        .addValue("tech", arrayJson(u.path("technologies")))
        .addValue("origin", u.path("origin").asString())
        .addValue("visibility", u.path("visibility").asString("shared"))
        .addValue("state", u.path("state").asString())
        .addValue("body", u.path("body").toString())
        .addValue("hash", u.path("content_hash").asString())
        .addValue("release", releaseId);
  }

  private void replacePrerequisites(JsonNode u) {
    String id = u.path("id").asString();
    jdbc.update("delete from unit_prereq where unit_id = :id", new MapSqlParameterSource("id", id));
    for (JsonNode pre : u.path("prerequisites")) {
      jdbc.update(
          "insert into unit_prereq (unit_id, prereq_id) values (:id, :pre)",
          new MapSqlParameterSource().addValue("id", id).addValue("pre", pre.asString()));
    }
  }

  private void replaceSources(JsonNode u) {
    String id = u.path("id").asString();
    jdbc.update("delete from unit_source where unit_id = :id", new MapSqlParameterSource("id", id));
    for (JsonNode s : u.path("sources")) {
      // Sources are shared across units, so the same book chapter becomes one row, not one per
      // unit. "do update" rather than "do nothing" so that returning gives the id either way.
      Long sourceId = jdbc.queryForObject(
          "insert into source (kind, title, url, locator, published, accessed)"
              + " values (:kind, :title, :url, :locator, :published, cast(:accessed as date))"
              + " on conflict (kind, title, url, locator)"
              + " do update set published = excluded.published, accessed = excluded.accessed"
              + " returning id",
          new MapSqlParameterSource()
              .addValue("kind", s.path("kind").asString())
              .addValue("title", s.path("title").asString())
              .addValue("url", textOrNull(s.path("url")))
              .addValue("locator", textOrNull(s.path("locator")))
              .addValue("published", textOrNull(s.path("published")))
              .addValue("accessed", textOrNull(s.path("accessed"))),
          Long.class);
      jdbc.update(
          "insert into unit_source (unit_id, source_id) values (:unit, :source)"
              + " on conflict do nothing",
          new MapSqlParameterSource().addValue("unit", id).addValue("source", sourceId));
    }
  }

  /**
   * A coding problem's cases or a SQL problem's fixture. They are part of the unit's content hash,
   * so they change only when the unit does.
   */
  private void replaceTests(JsonNode u) {
    MapSqlParameterSource id = new MapSqlParameterSource("id", u.path("id").asString());
    jdbc.update("delete from test_case where unit_id = :id", id);
    jdbc.update("delete from sql_fixture where unit_id = :id", id);
    JsonNode tests = u.path("tests");
    if (tests.has("reference")) {
      // The bundle has already put the shared dataset in front of the problem's own schema and seed.
      jdbc.update(
          "insert into sql_fixture (unit_id, schema_ddl, seed_sql, reference_query, order_matters)"
              + " values (:id, :schema, :seed, :reference, :ordered)",
          id.addValue("schema", tests.path("schema").asString())
              .addValue("seed", tests.path("seed").asString())
              .addValue("reference", tests.path("reference").asString())
              .addValue("ordered", tests.path("order_matters").asBoolean(false)));
    }
    int order = 0;
    for (JsonNode c : tests.path("cases")) {
      jdbc.update(
          "insert into test_case (unit_id, name, input, expected, hidden, sort_order)"
              + " values (:id, :name, :input, :expected, :hidden, :order)",
          new MapSqlParameterSource()
              .addValue("id", u.path("id").asString())
              .addValue("name", c.path("name").asString())
              .addValue("input", c.path("input").asString())
              .addValue("expected", c.path("expected").asString())
              .addValue("hidden", c.path("hidden").asBoolean(false))
              .addValue("order", order++));
    }
  }

  /** Tracks carry no learner data, so they are simply replaced with each release. */
  private void replaceTracks(JsonNode tracks) {
    jdbc.update("delete from track", new MapSqlParameterSource());
    for (JsonNode t : tracks) {
      String trackId = t.path("id").asString();
      jdbc.update(
          "insert into track (id, title, description) values (:id, :title, :description)",
          new MapSqlParameterSource()
              .addValue("id", trackId)
              .addValue("title", t.path("title").asString())
              .addValue("description", textOrNull(t.path("description"))));
      int order = 0;
      for (JsonNode m : t.path("modules")) {
        for (JsonNode unitId : m.path("units")) {
          jdbc.update(
              "insert into track_unit (track_id, module_id, unit_id, sort_order)"
                  + " values (:track, :module, :unit, :order)",
              new MapSqlParameterSource()
                  .addValue("track", trackId)
                  .addValue("module", m.path("id").asString())
                  .addValue("unit", unitId.asString())
                  .addValue("order", order++));
        }
      }
    }
  }

  static String textOrNull(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asString();
  }

  /** A JSON array literal, turned into text[] by the SQL. Avoids hand-escaping array literals. */
  static String arrayJson(JsonNode node) {
    return node.isArray() ? node.toString() : "[]";
  }
}
