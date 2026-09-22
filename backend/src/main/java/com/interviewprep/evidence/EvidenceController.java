package com.interviewprep.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The interview reports the curriculum rests on, as a learner browses them. Shared, not personal. */
@RestController
class EvidenceController {

  private final NamedParameterJdbcTemplate jdbc;

  EvidenceController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record Summary(String id, String companyId, String company, String role, String level,
      String location, String interviewDate, String sourceKind, String sourceTitle,
      String publisher, String tier, String outcome, int rounds, int questions, List<String> topics) {}

  record Topic(String id, String name) {}

  record Question(String text, List<Topic> topics) {}

  record Round(String type, String name, String summary, List<Question> questions) {}

  record Detail(Summary summary, String sourceUrl, String accessed, String notes, List<Round> rounds) {}

  record Option(String id, String name) {}

  record Options(List<Option> companies, List<Option> rounds) {}

  private static final String SUMMARY = "select e.id, e.company_id, c.name as company, e.role, e.level,"
      + " e.location, e.interview_date, e.source_kind, e.source_title, e.source_publisher, e.tier,"
      + " e.outcome, (select count(distinct round_index) from evidence_item i where i.evidence_id = e.id) as rounds,"
      + " (select count(*) from evidence_item i where i.evidence_id = e.id and i.question is not null) as questions,"
      + " coalesce((select array_agg(distinct it.topic_id) from evidence_item i"
      + "   join evidence_item_topic it on it.item_id = i.id where i.evidence_id = e.id), '{}') as topics"
      + " from evidence e join company c on c.id = e.company_id where e.state = 'accepted'";

  /** Newest first: a report without a date sorts last, since its age is unknown. */
  @GetMapping("/api/evidence")
  List<Summary> list() {
    return jdbc.query(SUMMARY + " order by e.interview_date desc nulls last, e.id",
        new MapSqlParameterSource(), (rs, i) -> summary(rs));
  }

  @GetMapping("/api/evidence/{id}")
  Detail detail(@PathVariable String id) {
    MapSqlParameterSource p = new MapSqlParameterSource("id", id);
    List<Summary> found = jdbc.query(SUMMARY + " and e.id = :id", p, (rs, i) -> summary(rs));
    if (found.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    Map<Integer, Round> rounds = new LinkedHashMap<>();
    jdbc.query("select i.id, i.round_index, i.round_type, coalesce(r.name, i.round_type) as round_name,"
            + " i.summary, i.question from evidence_item i left join round_type r on r.id = i.round_type"
            + " where i.evidence_id = :id order by i.sort_order", p, rs -> {
          String type = rs.getString("round_type");
          String name = rs.getString("round_name");
          String summary = rs.getString("summary");
          Round round = rounds.computeIfAbsent(rs.getInt("round_index"),
              k -> new Round(type, name, summary, new ArrayList<>()));
          String question = rs.getString("question");
          if (question != null) {
            round.questions().add(new Question(question, jdbc.query(
                "select t.id, t.name from evidence_item_topic it join topic t on t.id = it.topic_id"
                    + " where it.item_id = :item order by t.name",
                new MapSqlParameterSource("item", rs.getLong("id")),
                (r2, j) -> new Topic(r2.getString("id"), r2.getString("name")))));
          }
        });
    return jdbc.queryForObject("select source_url, accessed::text, notes from evidence where id = :id", p,
        (rs, i) -> new Detail(found.getFirst(), rs.getString(1), rs.getString(2), rs.getString(3),
            List.copyOf(rounds.values())));
  }

  /** What the debrief and report forms offer. */
  @GetMapping("/api/evidence/options")
  Options options() {
    return new Options(
        jdbc.query("select id, name from company where id <> 'general' order by name",
            new MapSqlParameterSource(), (rs, i) -> new Option(rs.getString(1), rs.getString(2))),
        jdbc.query("select id, name from round_type order by name",
            new MapSqlParameterSource(), (rs, i) -> new Option(rs.getString(1), rs.getString(2))));
  }

  private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Summary(rs.getString("id"), rs.getString("company_id"), rs.getString("company"),
        rs.getString("role"), rs.getString("level"), rs.getString("location"),
        rs.getString("interview_date"), rs.getString("source_kind"), rs.getString("source_title"),
        rs.getString("source_publisher"), rs.getString("tier"), rs.getString("outcome"),
        rs.getInt("rounds"), rs.getInt("questions"),
        List.of((String[]) rs.getArray("topics").getArray()));
  }
}
