package com.interviewprep.progress;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** A learner's attempts, and what follows from them, for other modules and this one's controller. */
@Component
public class ProgressQueries {

  /** Days run in India time, the same days the planner and the streak use. */
  public static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Kolkata");

  private final NamedParameterJdbcTemplate jdbc;

  ProgressQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Attempt(String unitId, String rating, OffsetDateTime at) {
    public LocalDate day() {
      return at.atZoneSameInstant(STUDY_ZONE).toLocalDate();
    }
  }

  /** All of a learner's attempts, oldest first: the order the review schedule replays them in. */
  public List<Attempt> attempts(long learnerId) {
    return query("learner_id = :learner", new MapSqlParameterSource("learner", learnerId));
  }

  List<Attempt> attempts(long learnerId, String unitId) {
    return query("learner_id = :learner and unit_id = :unit",
        new MapSqlParameterSource().addValue("learner", learnerId).addValue("unit", unitId));
  }

  /** When a unit is next due, from its attempts oldest first; null if it was never done. */
  public static LocalDate dueOn(List<Attempt> attempts) {
    return ReviewSchedule.dueOn(
        attempts.stream().map(a -> new ReviewSchedule.Step(a.rating(), a.day())).toList());
  }

  private List<Attempt> query(String where, MapSqlParameterSource params) {
    return jdbc.query(
        "select unit_id, rating, created_at from attempt where " + where + " order by created_at, id",
        params,
        (rs, i) -> new Attempt(rs.getString("unit_id"), rs.getString("rating"),
            rs.getObject("created_at", OffsetDateTime.class)));
  }
}
