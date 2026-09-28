package com.interviewprep.projects;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.progress.ReviewSchedule;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Rehearsals of a learner's project questions, and what follows from them. */
@Component
class ProjectQueries {

  private final NamedParameterJdbcTemplate jdbc;

  ProjectQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  record Rehearsal(long questionId, String rating, OffsetDateTime at) {
    ReviewSchedule.Step step() {
      return new ReviewSchedule.Step(rating, at.atZoneSameInstant(STUDY_ZONE).toLocalDate());
    }
  }

  /**
   * The same shape a unit's progress has, so the page can show both with one rating panel. A
   * question never rated has {@code attempts = 0} and nulls elsewhere.
   */
  record Progress(int attempts, OffsetDateTime firstDoneAt, OffsetDateTime lastAt, String lastRating,
      LocalDate dueOn, boolean reviewDue) {

    static Progress of(List<Rehearsal> rehearsals) {
      if (rehearsals.isEmpty()) {
        return new Progress(0, null, null, null, null, false);
      }
      Rehearsal last = rehearsals.getLast();
      LocalDate dueOn = ProjectQueries.dueOn(rehearsals);
      return new Progress(rehearsals.size(), rehearsals.getFirst().at(), last.at(), last.rating(), dueOn,
          !dueOn.isAfter(LocalDate.now(STUDY_ZONE)));
    }
  }

  static LocalDate dueOn(List<Rehearsal> rehearsals) {
    return ReviewSchedule.dueOn(rehearsals.stream().map(Rehearsal::step).toList());
  }

  /** Every rehearsal this learner has made, oldest first, grouped by question. */
  Map<Long, List<Rehearsal>> rehearsals(long learnerId) {
    Map<Long, List<Rehearsal>> byQuestion = new LinkedHashMap<>();
    for (Rehearsal r : query("learner_id = :learner", new MapSqlParameterSource("learner", learnerId))) {
      byQuestion.computeIfAbsent(r.questionId(), k -> new ArrayList<>()).add(r);
    }
    return byQuestion;
  }

  List<Rehearsal> rehearsals(long learnerId, long questionId) {
    return query("learner_id = :learner and question_id = :question",
        new MapSqlParameterSource().addValue("learner", learnerId).addValue("question", questionId));
  }

  /**
   * Refuses, as not found, a question that is not this learner's or has been retired. The other
   * learner's ids look exactly like ids that do not exist, so they cannot be probed for.
   */
  void requireOwn(long questionId, long learnerId) {
    Boolean own = jdbc.queryForObject(
        "select exists(select 1 from project_question q join experience_project p on p.id = q.project_id"
            + " where q.id = :question and p.learner_id = :learner and not q.retired and not p.retired)",
        new MapSqlParameterSource().addValue("question", questionId).addValue("learner", learnerId),
        Boolean.class);
    if (!Boolean.TRUE.equals(own)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private List<Rehearsal> query(String where, MapSqlParameterSource params) {
    return jdbc.query(
        "select question_id, rating, created_at from project_attempt where " + where
            + " order by created_at, id",
        params,
        (rs, i) -> new Rehearsal(rs.getLong("question_id"), rs.getString("rating"),
            rs.getObject("created_at", OffsetDateTime.class)));
  }
}
