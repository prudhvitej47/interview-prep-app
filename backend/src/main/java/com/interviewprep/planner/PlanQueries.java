package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import com.interviewprep.projects.ProjectPlanning;
import com.interviewprep.projects.ProjectPlanning.Rated;
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
  private final ProjectPlanning projects;

  PlanQueries(NamedParameterJdbcTemplate jdbc, ProgressQueries progress, ProjectPlanning projects) {
    this.jdbc = jdbc;
    this.progress = progress;
    this.projects = projects;
  }

  /**
   * A planned week and the minutes of it done: items whose unit got an attempt that week, and
   * project items whose question was rated that week. {@code planned} is the week's budget; its
   * items can add up to less, as nothing else fitted, so "all done" asks whether every planned item
   * is done: {@code itemMinutes} is their total and {@code itemsDone} the done part. Extras added
   * after finishing early count in {@code done} but in neither of those, so they never decide it.
   */
  public record WeekResult(LocalDate weekStart, int planned, int goal, int done, int itemMinutes, int itemsDone) {

    /** A week whose items fill its budget exactly. */
    public WeekResult(LocalDate weekStart, int planned, int goal, int done) {
      this(weekStart, planned, goal, done, planned, Math.min(done, planned));
    }

    public boolean goalMet() {
      return planned > 0 && done >= goal;
    }

    public boolean allDone() {
      return itemMinutes > 0 && itemsDone >= itemMinutes;
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
    return results(learnerId, progress.attempts(learnerId), projects.ratings(learnerId));
  }

  List<WeekResult> results(long learnerId, List<Attempt> attempts, List<Rated> projectRatings) {
    Map<LocalDate, Set<String>> doneByWeek = new HashMap<>();
    for (Attempt a : attempts) {
      doneByWeek.computeIfAbsent(mondayOf(a.day()), w -> new HashSet<>()).add(a.unitId());
    }
    Map<LocalDate, Set<Long>> ratedByWeek = new HashMap<>();
    for (Rated r : projectRatings) {
      ratedByWeek.computeIfAbsent(mondayOf(r.day()), w -> new HashSet<>()).add(r.questionId());
    }
    record Row(long plan, LocalDate week, int planned, int goal) {}
    List<Row> plans = jdbc.query(
        "select id, week_start, planned_minutes, goal_minutes from week_plan"
            + " where learner_id = :learner order by week_start",
        new MapSqlParameterSource("learner", learnerId),
        (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3), rs.getInt(4)));
    Map<Long, Integer> doneMinutes = new HashMap<>();
    Map<Long, Integer> itemMinutes = new HashMap<>();
    Map<Long, Integer> itemsDone = new HashMap<>();
    Map<Long, LocalDate> weekOf = new HashMap<>();
    plans.forEach(p -> weekOf.put(p.plan(), p.week()));
    jdbc.query(
        "select i.plan_id, i.unit_id, i.project_question_id, i.minutes, i.reason like :extra from plan_item i"
            + " join week_plan p on p.id = i.plan_id where p.learner_id = :learner",
        new MapSqlParameterSource("learner", learnerId).addValue("extra", WeekPlanner.EXTRA + "%"),
        rs -> {
          long plan = rs.getLong(1);
          LocalDate week = weekOf.get(plan);
          long question = rs.getLong(3);
          boolean done = rs.wasNull()
              ? doneByWeek.getOrDefault(week, Set.of()).contains(rs.getString(2))
              : ratedByWeek.getOrDefault(week, Set.of()).contains(question);
          int minutes = rs.getInt(4);
          if (done) {
            doneMinutes.merge(plan, minutes, Integer::sum);
          }
          if (!rs.getBoolean(5)) {
            itemMinutes.merge(plan, minutes, Integer::sum);
            if (done) {
              itemsDone.merge(plan, minutes, Integer::sum);
            }
          }
        });
    List<WeekResult> out = new ArrayList<>();
    plans.forEach(p -> out.add(new WeekResult(p.week(), p.planned(), p.goal(),
        doneMinutes.getOrDefault(p.plan(), 0), itemMinutes.getOrDefault(p.plan(), 0),
        itemsDone.getOrDefault(p.plan(), 0))));
    return out;
  }

  public Set<LocalDate> breaks(long learnerId) {
    return new TreeSet<>(jdbc.queryForList(
        "select week_start from planned_break where learner_id = :learner",
        new MapSqlParameterSource("learner", learnerId), LocalDate.class));
  }
}
