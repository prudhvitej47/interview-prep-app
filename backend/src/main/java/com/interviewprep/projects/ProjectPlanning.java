package com.interviewprep.projects;

import com.interviewprep.progress.Stage;
import com.interviewprep.projects.ProjectQueries.Rehearsal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What the planner and the rewards need to know about a learner's project questions, and nothing
 * more: which questions can be planned and when each is due, when each was rated, and how to name
 * one on the week page. The only part of this module other modules may use.
 */
@Component
public class ProjectPlanning {

  private final NamedParameterJdbcTemplate jdbc;
  private final ProjectQueries projects;

  ProjectPlanning(NamedParameterJdbcTemplate jdbc, ProjectQueries projects) {
    this.jdbc = jdbc;
    this.projects = projects;
  }

  /**
   * A question the planner may schedule. {@code projectOrder} and {@code questionOrder} are the
   * positions on the My projects page and on the project's ladder, from 0. {@code dueOn} is null
   * for a question never rated.
   */
  public record PlannableQuestion(long questionId, long projectId, String projectName, int projectOrder,
      int questionOrder, String rung, int minutes, LocalDate dueOn) {}

  /** One rating of a question, on its day in India time. */
  public record Rated(long questionId, String rating, LocalDate day) {}

  /** Enough to show a planned question: its prompt, rung and project. */
  public record QuestionRef(long questionId, long projectId, String projectName, String rung, String prompt) {}

  /** The learner's questions that are still in use (neither they nor their project retired), in ladder order. */
  public List<PlannableQuestion> plannable(long learnerId) {
    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(learnerId);
    List<PlannableQuestion> out = new ArrayList<>();
    Map<Long, Integer> projectOrder = new LinkedHashMap<>();
    Map<Long, Integer> questionOrder = new LinkedHashMap<>();
    jdbc.query(
        "select q.id, q.project_id, p.name, q.rung, q.minutes from project_question q"
            + " join experience_project p on p.id = q.project_id"
            + " where p.learner_id = :learner and not p.retired and not q.retired"
            + " order by p.sort_order, p.id, q.sort_order, q.id",
        new MapSqlParameterSource("learner", learnerId),
        rs -> {
          long question = rs.getLong("id");
          long project = rs.getLong("project_id");
          projectOrder.putIfAbsent(project, projectOrder.size());
          int inProject = questionOrder.merge(project, 1, Integer::sum) - 1;
          List<Rehearsal> r = rehearsals.get(question);
          out.add(new PlannableQuestion(question, project, rs.getString("name"), projectOrder.get(project),
              inProject, rs.getString("rung"), rs.getInt("minutes"), r == null ? null : ProjectQueries.dueOn(r)));
        });
    return out;
  }

  /** A question still in use and where the learner stands with it, for the progress page. */
  public record QuestionStanding(long questionId, long projectId, String projectName, String rung, String prompt,
      Stage.Standing standing) {}

  /**
   * Every question still in use (neither it nor its project retired), in the order of the My
   * projects page and each project's ladder, with its stage as of {@code today} in India time.
   */
  public List<QuestionStanding> standings(long learnerId, LocalDate today) {
    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(learnerId);
    return jdbc.query(
        "select q.id, q.project_id, p.name, q.rung, q.prompt from project_question q"
            + " join experience_project p on p.id = q.project_id"
            + " where p.learner_id = :learner and not p.retired and not q.retired"
            + " order by p.sort_order, p.id, q.sort_order, q.id",
        new MapSqlParameterSource("learner", learnerId),
        (rs, i) -> new QuestionStanding(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
            rs.getString("rung"), rs.getString("prompt"),
            Stage.of(rehearsals.getOrDefault(rs.getLong("id"), List.of()).stream().map(Rehearsal::step).toList(),
                today)));
  }

  /** Every rating this learner has given, oldest first, including those of retired questions. */
  public List<Rated> ratings(long learnerId) {
    // Grouped by question; put back in time order, as the rewards read them.
    return projects.rehearsals(learnerId).values().stream().flatMap(List::stream)
        .sorted(Comparator.comparing(Rehearsal::at))
        .map(r -> new Rated(r.questionId(), r.rating(), r.step().on()))
        .toList();
  }

  /** The named questions that are this learner's and still in use; retired ones are left out. */
  public Map<Long, QuestionRef> describe(long learnerId, Collection<Long> questionIds) {
    Map<Long, QuestionRef> out = new LinkedHashMap<>();
    if (questionIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "select q.id, q.project_id, p.name, q.rung, q.prompt from project_question q"
            + " join experience_project p on p.id = q.project_id"
            + " where q.id in (:ids) and p.learner_id = :learner and not p.retired and not q.retired",
        new MapSqlParameterSource().addValue("ids", questionIds).addValue("learner", learnerId),
        rs -> {
          out.put(rs.getLong("id"), new QuestionRef(rs.getLong("id"), rs.getLong("project_id"),
              rs.getString("name"), rs.getString("rung"), rs.getString("prompt")));
        });
    return out;
  }
}
