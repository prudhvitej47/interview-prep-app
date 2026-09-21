package com.interviewprep.progress;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.CurriculumQueries.UnitSummary;
import com.interviewprep.learner.LearnerPrincipal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Marking a unit done, reviewing it later, and the queue of reviews that are due.
 *
 * <p>Progress changes only here, and only when a learner says how an attempt went. Nothing is
 * inferred from reading a page or running a query, so looking around never counts as studying.
 */
@RestController
class AttemptController {

  private static final Set<String> RATINGS = Set.of("again", "hard", "good", "easy");
  // Days run in India time, the same days the planner and the streak use.
  private static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Kolkata");

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;

  AttemptController(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
  }

  /**
   * A unit never attempted has {@code attempts = 0} and nulls elsewhere. {@code reviewDue} is decided
   * here, in India time, so the browser needs no calendar logic of its own.
   */
  record Progress(int attempts, OffsetDateTime firstDoneAt, OffsetDateTime lastAt, String lastRating,
      LocalDate dueOn, boolean reviewDue) {}

  record Rating(String rating) {}

  record Due(String unitId, String title, String type, int estMinutes, LocalDate dueOn) {}

  /** What is due today or overdue, oldest first, and when the next one after that falls. */
  record ReviewQueue(List<Due> due, LocalDate nextDueOn) {}

  private record Row(String unitId, String rating, OffsetDateTime at) {}

  @GetMapping("/api/units/{unitId}/progress")
  Progress progress(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireVisible(unitId, me);
    return progressOf(unitId, me);
  }

  @PostMapping("/api/units/{unitId}/attempts")
  Progress record(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody Rating request) {
    requireVisible(unitId, me);
    if (request == null || !RATINGS.contains(request.rating())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be one of " + RATINGS);
    }
    jdbc.update("insert into attempt (learner_id, unit_id, rating) values (:learner, :unit, :rating)",
        params(unitId, me).addValue("rating", request.rating()));
    return progressOf(unitId, me);
  }

  /** Takes back the latest attempt: a mis-click, or "not done after all". */
  @DeleteMapping("/api/units/{unitId}/attempts/latest")
  Progress undo(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireVisible(unitId, me);
    jdbc.update(
        "delete from attempt where id = (select id from attempt where learner_id = :learner"
            + " and unit_id = :unit order by created_at desc, id desc limit 1)",
        params(unitId, me));
    return progressOf(unitId, me);
  }

  @GetMapping("/api/reviews")
  ReviewQueue reviews(@AuthenticationPrincipal LearnerPrincipal me) {
    Map<String, List<Row>> byUnit = new LinkedHashMap<>();
    for (Row r : rows("learner_id = :learner", new MapSqlParameterSource("learner", me.id()))) {
      byUnit.computeIfAbsent(r.unitId(), k -> new ArrayList<>()).add(r);
    }
    // Retired units and units no longer visible drop out of the queue; their history stays.
    Map<String, UnitSummary> units = new LinkedHashMap<>();
    curriculum.summaries(byUnit.keySet(), me.slug()).forEach(u -> units.put(u.id(), u));

    LocalDate today = LocalDate.now(STUDY_ZONE);
    List<Due> due = new ArrayList<>();
    LocalDate next = null;
    for (var entry : byUnit.entrySet()) {
      UnitSummary unit = units.get(entry.getKey());
      if (unit == null) {
        continue;
      }
      LocalDate dueOn = dueOn(entry.getValue());
      if (!dueOn.isAfter(today)) {
        due.add(new Due(unit.id(), unit.title(), unit.type(), unit.estMinutes(), dueOn));
      } else if (next == null || dueOn.isBefore(next)) {
        next = dueOn;
      }
    }
    due.sort(Comparator.comparing(Due::dueOn).thenComparing(Due::title));
    return new ReviewQueue(due, next);
  }

  private Progress progressOf(String unitId, LearnerPrincipal me) {
    List<Row> attempts = rows("learner_id = :learner and unit_id = :unit", params(unitId, me));
    if (attempts.isEmpty()) {
      return new Progress(0, null, null, null, null, false);
    }
    Row last = attempts.getLast();
    LocalDate dueOn = dueOn(attempts);
    return new Progress(attempts.size(), attempts.getFirst().at(), last.at(), last.rating(), dueOn,
        !dueOn.isAfter(LocalDate.now(STUDY_ZONE)));
  }

  private static LocalDate dueOn(List<Row> attempts) {
    return ReviewSchedule.dueOn(attempts.stream()
        .map(r -> new ReviewSchedule.Step(r.rating(), r.at().atZoneSameInstant(STUDY_ZONE).toLocalDate()))
        .toList());
  }

  /** Oldest first, which is the order the schedule replays them in. */
  private List<Row> rows(String where, MapSqlParameterSource params) {
    return jdbc.query(
        "select unit_id, rating, created_at from attempt where " + where + " order by created_at, id",
        params,
        (rs, i) -> new Row(rs.getString("unit_id"), rs.getString("rating"),
            rs.getObject("created_at", OffsetDateTime.class)));
  }

  // Someone else's private unit is a 404 here too, so progress cannot be used to probe for it.
  private void requireVisible(String unitId, LearnerPrincipal me) {
    if (!curriculum.isVisible(unitId, me.slug())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static MapSqlParameterSource params(String unitId, LearnerPrincipal me) {
    return new MapSqlParameterSource().addValue("learner", me.id()).addValue("unit", unitId);
  }
}
