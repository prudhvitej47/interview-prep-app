package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** How each planned week went, and which weeks are breaks, for this module and gamification. */
@Component
public class PlanQueries {

  private final NamedParameterJdbcTemplate jdbc;
  private final ProgressQueries progress;

  PlanQueries(NamedParameterJdbcTemplate jdbc, ProgressQueries progress) {
    this.jdbc = jdbc;
    this.progress = progress;
  }

  /** A planned week and the minutes of it done: items whose unit got an attempt that week. */
  public record WeekResult(LocalDate weekStart, int planned, int goal, int done) {
    public boolean goalMet() {
      return planned > 0 && done >= goal;
    }

    public boolean allDone() {
      return planned > 0 && done >= planned;
    }
  }

  public static LocalDate mondayOf(LocalDate day) {
    return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  public static LocalDate thisMonday() {
    return mondayOf(LocalDate.now(STUDY_ZONE));
  }

  /** Every week this learner has had a plan for, oldest first. */
  public List<WeekResult> results(long learnerId) {
    return results(learnerId, progress.attempts(learnerId));
  }

  List<WeekResult> results(long learnerId, List<Attempt> attempts) {
    Map<LocalDate, Set<String>> doneByWeek = new HashMap<>();
    for (Attempt a : attempts) {
      doneByWeek.computeIfAbsent(mondayOf(a.day()), w -> new HashSet<>()).add(a.unitId());
    }
    record Row(long plan, LocalDate week, int planned, int goal) {}
    List<Row> plans = jdbc.query(
        "select id, week_start, planned_minutes, goal_minutes from week_plan"
            + " where learner_id = :learner order by week_start",
        new MapSqlParameterSource("learner", learnerId),
        (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3), rs.getInt(4)));
    Map<Long, Integer> doneMinutes = new HashMap<>();
    Map<Long, LocalDate> weekOf = new HashMap<>();
    plans.forEach(p -> weekOf.put(p.plan(), p.week()));
    jdbc.query(
        "select i.plan_id, i.unit_id, i.minutes from plan_item i join week_plan p on p.id = i.plan_id"
            + " where p.learner_id = :learner",
        new MapSqlParameterSource("learner", learnerId),
        rs -> {
          long plan = rs.getLong(1);
          if (doneByWeek.getOrDefault(weekOf.get(plan), Set.of()).contains(rs.getString(2))) {
            doneMinutes.merge(plan, rs.getInt(3), Integer::sum);
          }
        });
    List<WeekResult> out = new ArrayList<>();
    plans.forEach(p -> out.add(new WeekResult(p.week(), p.planned(), p.goal(),
        doneMinutes.getOrDefault(p.plan(), 0))));
    return out;
  }

  public Set<LocalDate> breaks(long learnerId) {
    return new TreeSet<>(jdbc.queryForList(
        "select week_start from planned_break where learner_id = :learner",
        new MapSqlParameterSource("learner", learnerId), LocalDate.class));
  }
}
