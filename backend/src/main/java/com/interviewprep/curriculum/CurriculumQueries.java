package com.interviewprep.curriculum;

import java.util.List;
import java.util.Optional;
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

  private final NamedParameterJdbcTemplate jdbc;

  CurriculumQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record UnitSummary(String id, String title, String type, int difficulty, int estMinutes) {}

  public record TopicDetail(
      String id, String name, String domainId, String domainName, String parentId, String parentName,
      List<TopicSummary> subtopics, List<UnitSummary> units) {}

  public record Source(String kind, String title, String url, String locator) {}

  public record Link(String id, String title) {}

  public record TestCase(String name, String input, String expected) {}

  /** Everything the browser needs to run a SQL problem in PGlite and work out the right answer. */
  public record SqlFixture(String schema, String seed, String reference, boolean orderMatters) {}

  /**
   * Hidden test cases are counted, never sent: they are kept for the code runner planned for
   * phase 2, and sending them would make them visible to anyone who opens the network tab.
   */
  public record UnitDetail(
      String id, String title, String type, int difficulty, int estMinutes, List<String> rounds,
      List<String> technologies, String origin, String state, int version, String markdown,
      String topicId, String topicName, String domainId, String domainName,
      List<Source> sources, List<Link> prerequisites, List<TestCase> testCases,
      int hiddenTestCases, SqlFixture sqlFixture) {}

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
                "select u.id, u.title, u.type, u.difficulty, u.est_minutes from unit u"
                    + " where u.topic_id = :id and u.state <> 'retired' and " + VISIBLE
                    + " order by u.difficulty, u.title",
                p,
                (rs2, j) -> new UnitSummary(rs2.getString("id"), rs2.getString("title"),
                    rs2.getString("type"), rs2.getInt("difficulty"), rs2.getInt("est_minutes")))));
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
                .stream().findFirst().orElse(null)));
    return found.stream().findFirst();
  }

  /** Whether this learner may see this unit. Other modules use it before attaching data to one. */
  public boolean isVisible(String unitId, String slug) {
    Boolean visible = jdbc.queryForObject(
        "select exists(select 1 from unit u where u.id = :id and " + VISIBLE + ")",
        forLearner(slug).addValue("id", unitId), Boolean.class);
    return Boolean.TRUE.equals(visible);
  }
}
