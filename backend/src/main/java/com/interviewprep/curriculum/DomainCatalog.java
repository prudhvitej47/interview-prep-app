package com.interviewprep.curriculum;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The curriculum's domains, in curriculum order. Other modules use this rather than the tables. */
@Component
public class DomainCatalog {

  private final JdbcTemplate jdbc;

  DomainCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<DomainSummary> all() {
    // Up to four top-level topics each, so "Databases and SQL" arrives with enough context to rate.
    return jdbc.query(
        "select d.id, d.name, d.weight, coalesce(("
            + "  select array_agg(t.name order by t.sort_order) from ("
            + "    select name, sort_order from topic"
            + "    where domain_id = d.id and parent_id is null order by sort_order limit 4) t"
            + "), '{}') as examples"
            + " from domain d order by d.sort_order",
        (rs, i) -> {
          Array examples = rs.getArray("examples");
          return new DomainSummary(
              rs.getString("id"), rs.getString("name"), rs.getInt("weight"),
              Arrays.asList((String[]) examples.getArray()));
        });
  }
}
