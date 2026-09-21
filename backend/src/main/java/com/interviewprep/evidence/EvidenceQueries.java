package com.interviewprep.evidence;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Evidence as other modules need it. */
@Component
public class EvidenceQueries {

  private final JdbcTemplate jdbc;

  EvidenceQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * How many accepted reports ask about each topic. A report counts once per topic however many of
   * its questions touch it, so one detailed report cannot outweigh several independent ones.
   */
  public Map<String, Integer> reportsPerTopic() {
    Map<String, Integer> counts = new HashMap<>();
    jdbc.query(
        "select it.topic_id, count(distinct i.evidence_id) as reports"
            + " from evidence_item_topic it join evidence_item i on i.id = it.item_id"
            + " join evidence e on e.id = i.evidence_id where e.state = 'accepted'"
            + " group by it.topic_id",
        rs -> {
          counts.put(rs.getString("topic_id"), rs.getInt("reports"));
        });
    return counts;
  }
}
