package com.interviewprep.learner;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** What a learner has told the app about themselves, for the planner and this module's pages. */
@Component
public class LearnerProfile {

  private final NamedParameterJdbcTemplate jdbc;

  LearnerProfile(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * How much time a week, on which days (ISO numbers, 1 = Monday), and any domain weights that
   * replace the curriculum's defaults. A plan needs the first two.
   */
  public record WeekSettings(Double hoursPerWeek, List<Integer> studyDays, Map<String, Integer> weights) {
    public boolean complete() {
      return hoursPerWeek != null && studyDays != null && !studyDays.isEmpty();
    }
  }

  /** Self-ratings as given: per domain from onboarding, per topic when a learner sharpens one. */
  public record Ratings(Map<String, Integer> domains, Map<String, Integer> topics) {}

  public WeekSettings week(long learnerId) {
    MapSqlParameterSource p = new MapSqlParameterSource("learner", learnerId);
    Map<String, Integer> weights = new LinkedHashMap<>();
    jdbc.query("select domain_id, weight from learner_weight where learner_id = :learner"
        + " order by domain_id", p, rs -> {
          weights.put(rs.getString("domain_id"), rs.getInt("weight"));
        });
    return jdbc.queryForObject(
        "select hours_per_week, study_days from learner where id = :learner", p,
        (rs, i) -> {
          BigDecimal hours = rs.getBigDecimal("hours_per_week");
          Short[] days = (Short[]) rs.getArray("study_days").getArray();
          return new WeekSettings(hours == null ? null : hours.doubleValue(),
              Arrays.stream(days).map(Short::intValue).sorted().toList(), weights);
        });
  }

  public Ratings ratings(long learnerId) {
    Map<String, Integer> domains = new LinkedHashMap<>();
    Map<String, Integer> topics = new LinkedHashMap<>();
    jdbc.query("select scope, scope_id, rating from learner_competency where learner_id = :learner",
        new MapSqlParameterSource("learner", learnerId), rs -> {
          ("domain".equals(rs.getString("scope")) ? domains : topics)
              .put(rs.getString("scope_id"), rs.getInt("rating"));
        });
    return new Ratings(domains, topics);
  }
}
