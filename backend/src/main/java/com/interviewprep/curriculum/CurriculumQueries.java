package com.interviewprep.curriculum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the curriculum for one learner.
 *
 * <p>Every query is scoped by who is asking, because a unit can be private to one learner. That rule
 * is enforced here, in SQL, rather than by hiding things in the front end: a private unit is not
 * returned, not counted, and indistinguishable from one that does not exist.
 */
@Component
public class CurriculumQueries {

  // A unit is visible if it is shared, or private to the learner asking.
  private static final String VISIBLE = "(u.visibility = 'shared' or u.visibility = :private)";

  // A fenced block, from its opening ``` or ~~~ line to the matching close. Code in a block is code,
  // never a link, so it is dropped before looking for ids.
  private static final Pattern FENCE =
      Pattern.compile("(?ms)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n.*?^ {0,3}\\1");

  // An inline code span holding something shaped like a unit or topic id: dotted lower-case words.
  // `max.poll.interval.ms` matches too and is then simply not found in the curriculum.
  private static final Pattern ID_SPAN =
      Pattern.compile("(?<!`)`([a-z0-9]+(?:\\.[a-z0-9-]+)+)`(?!`)");

  private final NamedParameterJdbcTemplate jdbc;
  private final UnitsDone unitsDone;

  CurriculumQueries(NamedParameterJdbcTemplate jdbc, UnitsDone unitsDone) {
    this.jdbc = jdbc;
    this.unitsDone = unitsDone;
  }

  public record UnitSummary(String id, String title, String type, int difficulty, int estMinutes,
      boolean hasLab) {}

  public record TopicDetail(
      String id, String name, String domainId, String domainName, String parentId, String parentName,
      List<TopicSummary> subtopics, List<UnitSummary> units) {}

  public record Source(String kind, String title, String url, String locator) {}

  public record Link(String id, String title) {}

  /**
   * A unit or topic the unit's text names by id in inline code, with what the page needs to show
   * its title instead. {@code done} is whether this learner has marked the unit done; always false
   * for a topic.
   */
  public record Reference(String id, String kind, String title, String domainName, boolean done) {}

  public record TestCase(String name, String input, String expected) {}

  /** Everything the browser needs to run a SQL problem in PGlite and work out the right answer. */
  public record SqlFixture(String schema, String seed, String reference, boolean orderMatters) {}

  /** A unit's laptop lab as its page shows it. The salt and token hash stay on the server. */
  public record Lab(String title, int minutes, List<String> needs, String readme) {}

  /** One file of a lab's starter download: the fixed files and the learner's starter files. */
  public record LabFile(String name, String content) {}

  public record LabDownload(String title, String readme, List<LabFile> files) {}

  /** One case as the runner needs it: typed JSON exactly as the bundle has it. Server-only. */
  public record RunnerCase(String name, String argsJson, String expectedJson, boolean hidden) {}

  /** Everything the runner needs for one coding problem. Server-only: never part of UnitDetail. */
  public record CodingFixture(String signatureJson, String compare, List<RunnerCase> cases) {}

  /**
   * Hidden test cases are counted, never sent: they are kept for the code runner planned for
   * phase 2, and sending them would make them visible to anyone who opens the network tab.
   */
  public record UnitDetail(
      String id, String title, String type, int difficulty, int estMinutes, List<String> rounds,
      List<String> technologies, String origin, String state, int version, String markdown,
      String topicId, String topicName, String domainId, String domainName,
      List<Source> sources, List<Link> prerequisites, List<TestCase> testCases,
      int hiddenTestCases, SqlFixture sqlFixture, List<Reference> references, Lab lab) {}

  private static MapSqlParameterSource forLearner(String slug) {
    return new MapSqlParameterSource("private", "learner:" + slug);
  }

  /** Every topic in curriculum order, with how many units each holds for this learner. */
  public List<TopicSummary> topics(String slug) {
    return jdbc.query(
        "select t.id, t.domain_id, t.parent_id, t.name, (select count(*) from unit u"
            + "   join topic ut on ut.id = u.topic_id"
            + "   where (u.topic_id = t.id or ut.parent_id = t.id)"
            + "   and u.state <> 'retired' and " + VISIBLE + ") as unit_count"
            + " from topic t join domain d on d.id = t.domain_id"
            + " order by d.sort_order, t.sort_order",
        forLearner(slug),
        (rs, i) -> new TopicSummary(rs.getString("id"), rs.getString("domain_id"),
            rs.getString("parent_id"), rs.getString("name"), rs.getInt("unit_count")));
  }

  public Optional<TopicDetail> topic(String id, String slug) {
    MapSqlParameterSource p = forLearner(slug).addValue("id", id);
    List<TopicDetail> found = jdbc.query(
        "select t.id, t.name, t.parent_id, parent.name as parent_name, d.id as domain_id,"
            + " d.name as domain_name from topic t join domain d on d.id = t.domain_id"
            + " left join topic parent on parent.id = t.parent_id where t.id = :id",
        p,
        (rs, i) -> new TopicDetail(rs.getString("id"), rs.getString("name"),
            rs.getString("domain_id"), rs.getString("domain_name"), rs.getString("parent_id"),
            rs.getString("parent_name"),
            jdbc.query(
                "select s.id, s.domain_id, s.parent_id, s.name, (select count(*) from unit u"
                    + "   where u.topic_id = s.id and u.state <> 'retired' and " + VISIBLE + ")"
                    + "   as unit_count from topic s where s.parent_id = :id order by s.sort_order",
                p,
                (rs2, j) -> new TopicSummary(rs2.getString("id"), rs2.getString("domain_id"),
                    rs2.getString("parent_id"), rs2.getString("name"), rs2.getInt("unit_count"))),
            jdbc.query(
                "select u.id, u.title, u.type, u.difficulty, u.est_minutes,"
                    + " exists(select 1 from lab l where l.unit_id = u.id) as has_lab from unit u"
                    + " where u.topic_id = :id and u.state <> 'retired' and " + VISIBLE
                    + " order by u.difficulty, u.title",
                p,
                (rs2, j) -> new UnitSummary(rs2.getString("id"), rs2.getString("title"),
                    rs2.getString("type"), rs2.getInt("difficulty"), rs2.getInt("est_minutes"),
                    rs2.getBoolean("has_lab")))));
    return found.stream().findFirst();
  }

  /**
   * One unit, including a retired one: a learner's history may point at it, and the page says it is
   * retired rather than pretending it vanished.
   */
  public Optional<UnitDetail> unit(String id, String slug) {
    MapSqlParameterSource p = forLearner(slug).addValue("id", id);
    List<UnitDetail> found = jdbc.query(
        "select u.*, u.body ->> 'markdown' as markdown, t.name as topic_name,"
            + " d.id as domain_id, d.name as domain_name"
            + " from unit u join topic t on t.id = u.topic_id join domain d on d.id = t.domain_id"
            + " where u.id = :id and " + VISIBLE,
        p,
        (rs, i) -> new UnitDetail(
            rs.getString("id"), rs.getString("title"), rs.getString("type"),
            rs.getInt("difficulty"), rs.getInt("est_minutes"),
            List.of((String[]) rs.getArray("rounds").getArray()),
            List.of((String[]) rs.getArray("technologies").getArray()),
            rs.getString("origin"), rs.getString("state"), rs.getInt("version"),
            rs.getString("markdown"), rs.getString("topic_id"), rs.getString("topic_name"),
            rs.getString("domain_id"), rs.getString("domain_name"),
            jdbc.query(
                "select s.kind, s.title, s.url, s.locator from unit_source us"
                    + " join source s on s.id = us.source_id where us.unit_id = :id"
                    + " order by s.kind, s.title",
                p,
                (rs2, j) -> new Source(rs2.getString("kind"), rs2.getString("title"),
                    rs2.getString("url"), rs2.getString("locator"))),
            jdbc.query(
                "select u.id, u.title from unit_prereq pr join unit u on u.id = pr.prereq_id"
                    + " where pr.unit_id = :id and " + VISIBLE + " order by u.title",
                p,
                (rs2, j) -> new Link(rs2.getString("id"), rs2.getString("title"))),
            jdbc.query(
                "select name, input, expected from test_case where unit_id = :id and not hidden"
                    + " order by sort_order",
                p,
                (rs2, j) -> new TestCase(rs2.getString("name"), rs2.getString("input"),
                    rs2.getString("expected"))),
            jdbc.queryForObject(
                "select count(*) from test_case where unit_id = :id and hidden", p, Integer.class),
            jdbc.query(
                "select schema_ddl, seed_sql, reference_query, order_matters from sql_fixture"
                    + " where unit_id = :id",
                p,
                (rs2, j) -> new SqlFixture(rs2.getString("schema_ddl"), rs2.getString("seed_sql"),
                    rs2.getString("reference_query"), rs2.getBoolean("order_matters")))
                .stream().findFirst().orElse(null),
            references(rs.getString("markdown"), slug),
            jdbc.query("select title, minutes, needs, readme from lab where unit_id = :id", p,
                (rs2, j) -> new Lab(rs2.getString("title"), rs2.getInt("minutes"),
                    List.of((String[]) rs2.getArray("needs").getArray()), rs2.getString("readme")))
                .stream().findFirst().orElse(null)));
    return found.stream().findFirst();
  }

  /**
   * The units and topics {@code markdown} names in inline code, outside fenced blocks, in the order
   * they first appear. Units this learner cannot see, retired units and anything that is not an id
   * are left out, so the page shows them as the plain code they were. Two queries however many ids
   * the text holds: one for titles, one for which units are done.
   */
  List<Reference> references(String markdown, String slug) {
    Set<String> ids = new LinkedHashSet<>();
    Matcher span = ID_SPAN.matcher(FENCE.matcher(markdown == null ? "" : markdown).replaceAll(""));
    while (span.find()) {
      ids.add(span.group(1));
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    record Found(String kind, String title, String domainName) {}
    Map<String, Found> found = new HashMap<>();
    jdbc.query(
        "select u.id, 'unit' as kind, u.title, d.name as domain_name from unit u"
            + " join topic t on t.id = u.topic_id join domain d on d.id = t.domain_id"
            + " where u.id in (:ids) and u.state <> 'retired' and " + VISIBLE
            + " union all select t.id, 'topic', t.name, d.name from topic t"
            + " join domain d on d.id = t.domain_id where t.id in (:ids)",
        forLearner(slug).addValue("ids", ids),
        rs -> {
          // Should an id ever name both a unit and a topic, the unit wins: it is the thing to read.
          found.merge(rs.getString("id"),
              new Found(rs.getString("kind"), rs.getString("title"), rs.getString("domain_name")),
              (a, b) -> a.kind().equals("unit") ? a : b);
        });
    Set<String> done = unitsDone.doneAmong(
        found.entrySet().stream().filter(e -> e.getValue().kind().equals("unit")).map(Map.Entry::getKey)
            .toList(),
        slug);
    return ids.stream().filter(found::containsKey).map(id -> {
      Found f = found.get(id);
      return new Reference(id, f.kind(), f.title(), f.domainName(), done.contains(id));
    }).toList();
  }

  /** A unit the planner can choose from, with what it needs to rank and place it. */
  public record PlannableUnit(String id, String title, String type, String topicId, String topicName,
      String domainId, int difficulty, int estMinutes, List<String> prerequisites) {}

  /** Where a topic sits: its parent (null at the top) and its domain. */
  public record TopicPlace(String name, String parentId, String domainId) {}

  /** Every unit this learner can see that is still in the curriculum. */
  public List<PlannableUnit> plannable(String slug) {
    return jdbc.query(
        "select u.id, u.title, u.type, u.topic_id, t.name as topic_name, t.domain_id, u.difficulty,"
            + " u.est_minutes, coalesce((select array_agg(pr.prereq_id order by pr.prereq_id)"
            + "   from unit_prereq pr where pr.unit_id = u.id), '{}') as prereqs"
            + " from unit u join topic t on t.id = u.topic_id"
            + " where u.state <> 'retired' and " + VISIBLE + " order by u.id",
        forLearner(slug),
        (rs, i) -> new PlannableUnit(rs.getString("id"), rs.getString("title"), rs.getString("type"),
            rs.getString("topic_id"), rs.getString("topic_name"), rs.getString("domain_id"),
            rs.getInt("difficulty"), rs.getInt("est_minutes"),
            List.of((String[]) rs.getArray("prereqs").getArray())));
  }

  /**
   * The type of each unit among {@code ids}, retired ones included: stars earned on a unit stay
   * earned after it leaves the curriculum.
   */
  public Map<String, String> typesOf(Collection<String> ids) {
    Map<String, String> types = new HashMap<>();
    if (!ids.isEmpty()) {
      jdbc.query("select id, type from unit where id in (:ids)", new MapSqlParameterSource("ids", ids),
          rs -> {
            types.put(rs.getString(1), rs.getString(2));
          });
    }
    return types;
  }

  public record ReleaseUnit(String id, String title, String type, boolean added) {}

  public record Release(long id, String version, java.time.OffsetDateTime createdAt, String changelog,
      int added, int changed, int retired, List<ReleaseUnit> units) {}

  /**
   * The newest release that added, changed or retired units, and the units it added or changed
   * that this learner can see. A release that only touched evidence or the taxonomy has nothing to
   * place, so it is passed over. A unit's {@code release_id} is the release it last changed in,
   * and version 1 means it arrived then.
   */
  public Release latestRelease(String slug) {
    Long id = jdbc.query("select max(id) from curriculum_release"
            + " where units_added + units_changed + units_retired > 0",
        rs -> rs.next() ? (Long) rs.getObject(1) : null);
    if (id == null) {
      return null;
    }
    MapSqlParameterSource p = forLearner(slug).addValue("release", id);
    List<ReleaseUnit> units = jdbc.query(
        "select u.id, u.title, u.type, u.version from unit u where u.release_id = :release"
            + " and u.state <> 'retired' and " + VISIBLE + " order by u.version, u.id",
        p, (rs, i) -> new ReleaseUnit(rs.getString("id"), rs.getString("title"), rs.getString("type"),
            rs.getInt("version") == 1));
    return jdbc.queryForObject(
        "select version, created_at, changelog, units_added, units_changed, units_retired"
            + " from curriculum_release where id = :release",
        p, (rs, i) -> new Release(id, rs.getString("version"),
            rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getString("changelog"),
            rs.getInt("units_added"), rs.getInt("units_changed"), rs.getInt("units_retired"), units));
  }

  /** The newest curriculum release, or null before the first load. */
  public Long latestReleaseId() {
    return jdbc.query("select max(id) from curriculum_release",
        rs -> rs.next() ? (Long) rs.getObject(1) : null);
  }

  /** Every topic by id. */
  public Map<String, TopicPlace> topicPlaces() {
    Map<String, TopicPlace> places = new HashMap<>();
    jdbc.query("select id, name, parent_id, domain_id from topic", rs -> {
      places.put(rs.getString("id"),
          new TopicPlace(rs.getString("name"), rs.getString("parent_id"), rs.getString("domain_id")));
    });
    return places;
  }

  /**
   * The units among {@code ids} this learner can see and that are still in the curriculum, in no
   * particular order. Other modules use it to put titles on lists of their own rows.
   */
  public List<UnitSummary> summaries(Collection<String> ids, String slug) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "select u.id, u.title, u.type, u.difficulty, u.est_minutes,"
            + " exists(select 1 from lab l where l.unit_id = u.id) as has_lab from unit u"
            + " where u.id in (:ids) and u.state <> 'retired' and " + VISIBLE,
        forLearner(slug).addValue("ids", ids),
        (rs, i) -> new UnitSummary(rs.getString("id"), rs.getString("title"), rs.getString("type"),
            rs.getInt("difficulty"), rs.getInt("est_minutes"), rs.getBoolean("has_lab")));
  }

  public boolean hasLab(String unitId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from lab where unit_id = :id)", new MapSqlParameterSource("id", unitId),
        Boolean.class));
  }

  /**
   * Whether {@code token} is the proof token of this unit's lab: sha256(salt + token) equals the stored
   * hash. The caller normalises the token first. Compared in constant time out of habit; the token is
   * not a credential.
   */
  public boolean labTokenMatches(String unitId, String token) {
    List<String[]> rows = jdbc.query("select salt, token_hash from lab where unit_id = :id",
        new MapSqlParameterSource("id", unitId), (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    if (rows.isEmpty()) {
      return false;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((rows.get(0)[0] + token).getBytes(StandardCharsets.UTF_8));
      String actual = "sha256:" + HexFormat.of().formatHex(digest);
      return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
          rows.get(0)[1].getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** What the starter download holds, or empty if the unit has no lab. Visibility is the caller's check. */
  public Optional<LabDownload> labDownload(String unitId) {
    MapSqlParameterSource p = new MapSqlParameterSource("id", unitId);
    return jdbc.query("select title, readme from lab where unit_id = :id", p,
        (rs, i) -> new LabDownload(rs.getString("title"), rs.getString("readme"),
            jdbc.query("select f.name, f.content from lab l"
                    + " cross join jsonb_to_recordset(l.files) as f(name text, content text)"
                    + " where l.unit_id = :id order by f.name", p,
                (rs2, j) -> new LabFile(rs2.getString("name"), rs2.getString("content")))))
        .stream().findFirst();
  }

  /**
   * Everything the code runner needs for one coding problem, hidden cases included. Server-only: the
   * runner client is its one caller, and none of it is ever part of {@link UnitDetail}. Empty for a
   * unit that is not a coding problem or was loaded before its fixture was kept (see BundleLoader).
   */
  public Optional<CodingFixture> codingFixture(String unitId) {
    MapSqlParameterSource p = new MapSqlParameterSource("id", unitId);
    return jdbc.query("select signature::text as signature, compare from coding_fixture where unit_id = :id", p,
        (rs, i) -> new CodingFixture(rs.getString("signature"), rs.getString("compare"),
            jdbc.query("select name, args::text as args, expected_value::text as expected_value, hidden"
                    + " from test_case where unit_id = :id order by sort_order", p,
                (rs2, j) -> new RunnerCase(rs2.getString("name"), rs2.getString("args"),
                    rs2.getString("expected_value"), rs2.getBoolean("hidden")))))
        .stream().findFirst();
  }

  /** Whether this learner may see this unit. Other modules use it before attaching data to one. */
  public boolean isVisible(String unitId, String slug) {
    Boolean visible = jdbc.queryForObject(
        "select exists(select 1 from unit u where u.id = :id and " + VISIBLE + ")",
        forLearner(slug).addValue("id", unitId), Boolean.class);
    return Boolean.TRUE.equals(visible);
  }
}
