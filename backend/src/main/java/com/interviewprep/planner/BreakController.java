package com.interviewprep.planner;

import com.interviewprep.learner.LearnerPrincipal;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
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
 * Planned breaks (proposal G): up to two weeks a quarter, declared before the week starts. Only
 * future weeks can be taken or given back, so a break can never be used to rescue a week that is
 * already going badly — that is what freezes are for.
 */
@RestController
class BreakController {

  static final int PER_QUARTER = 2;
  // How far ahead the page offers weeks; a break further out can be taken nearer the time.
  static final int WEEKS_AHEAD = 12;

  private final NamedParameterJdbcTemplate jdbc;
  private final PlanQueries plans;

  BreakController(NamedParameterJdbcTemplate jdbc, PlanQueries plans) {
    this.jdbc = jdbc;
    this.plans = plans;
  }

  record Choice(LocalDate weekStart, boolean taken, boolean available) {}

  record Breaks(boolean thisWeek, List<Choice> upcoming) {}

  record Request(LocalDate weekStart) {}

  @GetMapping("/api/breaks")
  Breaks list(@AuthenticationPrincipal LearnerPrincipal me) {
    Set<LocalDate> taken = plans.breaks(me.id());
    LocalDate monday = PlanQueries.thisMonday();
    List<Choice> upcoming = new ArrayList<>();
    for (int i = 1; i <= WEEKS_AHEAD; i++) {
      LocalDate week = monday.plusWeeks(i);
      boolean isTaken = taken.contains(week);
      upcoming.add(new Choice(week, isTaken, isTaken || inQuarter(taken, week) < PER_QUARTER));
    }
    return new Breaks(taken.contains(monday), upcoming);
  }

  @PostMapping("/api/breaks")
  Breaks take(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody Request request) {
    LocalDate week = requireFutureMonday(request == null ? null : request.weekStart());
    Set<LocalDate> taken = plans.breaks(me.id());
    if (!taken.contains(week) && inQuarter(taken, week) >= PER_QUARTER) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "at most " + PER_QUARTER + " break weeks a quarter");
    }
    jdbc.update("insert into planned_break (learner_id, week_start) values (:learner, :week)"
        + " on conflict do nothing", params(me, week));
    return list(me);
  }

  @DeleteMapping("/api/breaks/{weekStart}")
  Breaks giveBack(@AuthenticationPrincipal LearnerPrincipal me, @PathVariable LocalDate weekStart) {
    jdbc.update("delete from planned_break where learner_id = :learner and week_start = :week",
        params(me, requireFutureMonday(weekStart)));
    return list(me);
  }

  private static LocalDate requireFutureMonday(LocalDate week) {
    LocalDate monday = PlanQueries.thisMonday();
    if (week == null || !week.equals(PlanQueries.mondayOf(week)) || !week.isAfter(monday)
        || week.isAfter(monday.plusWeeks(WEEKS_AHEAD))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "a break is a Monday from next week up to " + WEEKS_AHEAD + " weeks ahead");
    }
    return week;
  }

  /** Breaks in the calendar quarter the week starts in. */
  private static long inQuarter(Set<LocalDate> taken, LocalDate week) {
    return taken.stream().filter(t -> t.getYear() == week.getYear()
        && t.get(IsoFields.QUARTER_OF_YEAR) == week.get(IsoFields.QUARTER_OF_YEAR)).count();
  }

  private static MapSqlParameterSource params(LearnerPrincipal me, LocalDate week) {
    return new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", week);
  }
}
