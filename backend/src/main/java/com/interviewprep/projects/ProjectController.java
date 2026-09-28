package com.interviewprep.projects;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.progress.ReviewSchedule;
import com.interviewprep.projects.ProjectQueries.Progress;
import com.interviewprep.projects.ProjectQueries.Rehearsal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A learner's projects and the questions about them: reading them, writing an answer, and saying
 * how a rehearsal went. Adding and changing questions is done by re-importing the file
 * ({@link ProjectFileController}), not here.
 */
@RestController
class ProjectController {

  // The same cap as a unit note: a long answer outline fits, a paste by mistake does not.
  static final int MAX_ANSWER = 20_000;

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;
  private final ProjectQueries projects;

  ProjectController(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum, ProjectQueries projects) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
    this.projects = projects;
  }

  /** One row of the My projects list. "Answered" means an answer has been written down. */
  record ProjectSummary(long id, String name, int questions, int answered, int due) {}

  record UnitLink(String id, String title) {}

  record Question(long id, String rung, String prompt, String probes, String strongAnswer,
      List<UnitLink> units, int minutes, String answer, OffsetDateTime answeredAt, Progress progress) {}

  record Project(long id, String name, String summary, List<String> topics, List<Question> questions) {}

  record AnswerBody(String answer) {}

  record Answer(String answer, OffsetDateTime answeredAt) {}

  record Rating(String rating) {}

  record Due(long questionId, long projectId, String projectName, String rung, String prompt, int minutes,
      LocalDate dueOn) {}

  /** What is due today or overdue, oldest first, and when the next one after that falls. */
  record ReviewQueue(List<Due> due, LocalDate nextDueOn) {}

  @GetMapping("/api/projects")
  List<ProjectSummary> list(@AuthenticationPrincipal LearnerPrincipal me) {
    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(me.id());
    LocalDate today = LocalDate.now(STUDY_ZONE);
    Map<Long, int[]> counts = new LinkedHashMap<>();
    Map<Long, String> names = new LinkedHashMap<>();
    jdbc.query(
        "select p.id, p.name, q.id as question_id, q.answer <> '' as answered from experience_project p"
            + " left join project_question q on q.project_id = p.id and not q.retired"
            + " where p.learner_id = :learner and not p.retired order by p.sort_order, p.id",
        new MapSqlParameterSource("learner", me.id()),
        rs -> {
          long project = rs.getLong("id");
          names.put(project, rs.getString("name"));
          int[] c = counts.computeIfAbsent(project, k -> new int[3]);
          long question = rs.getLong("question_id");
          if (rs.wasNull()) {
            return;
          }
          c[0]++;
          if (rs.getBoolean("answered")) {
            c[1]++;
          }
          List<Rehearsal> r = rehearsals.get(question);
          if (r != null && !ProjectQueries.dueOn(r).isAfter(today)) {
            c[2]++;
          }
        });
    return names.entrySet().stream()
        .map(e -> {
          int[] c = counts.get(e.getKey());
          return new ProjectSummary(e.getKey(), e.getValue(), c[0], c[1], c[2]);
        })
        .toList();
  }

  @GetMapping("/api/projects/{projectId}")
  Project project(@PathVariable long projectId, @AuthenticationPrincipal LearnerPrincipal me) {
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("project", projectId).addValue("learner", me.id());
    // The other learner's project is a 404, the same as one that does not exist.
    Project head = jdbc.query(
            "select id, name, summary, topic_ids from experience_project"
                + " where id = :project and learner_id = :learner and not retired",
            params,
            (rs, i) -> new Project(rs.getLong("id"), rs.getString("name"),
                rs.getString("summary") == null ? "" : rs.getString("summary"),
                strings(rs.getArray("topic_ids")), List.of()))
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    record Row(long id, String rung, String prompt, String probes, String strongAnswer, List<String> units,
        int minutes, String answer, OffsetDateTime answeredAt) {}
    List<Row> rows = jdbc.query(
        "select id, rung, prompt, probes, strong_answer, unit_ids, minutes, answer, answered_at"
            + " from project_question where project_id = :project and not retired order by sort_order, id",
        params,
        (rs, i) -> new Row(rs.getLong("id"), rs.getString("rung"), rs.getString("prompt"),
            rs.getString("probes"), rs.getString("strong_answer"), strings(rs.getArray("unit_ids")),
            rs.getInt("minutes"), rs.getString("answer"), rs.getObject("answered_at", OffsetDateTime.class)));

    // Titles for the linked units, in one query. A unit retired or gone since the import just
    // drops off the list; the next import reports it.
    Set<String> unitIds = new LinkedHashSet<>();
    rows.forEach(r -> unitIds.addAll(r.units()));
    Map<String, String> titles = new LinkedHashMap<>();
    curriculum.summaries(unitIds, me.slug()).forEach(u -> titles.put(u.id(), u.title()));

    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(me.id());
    List<Question> questions = rows.stream()
        .map(r -> new Question(r.id(), r.rung(), r.prompt(), r.probes(), r.strongAnswer(),
            r.units().stream().filter(titles::containsKey).map(u -> new UnitLink(u, titles.get(u))).toList(),
            r.minutes(), r.answer(), r.answeredAt(),
            Progress.of(rehearsals.getOrDefault(r.id(), List.of()))))
        .toList();
    return new Project(head.id(), head.name(), head.summary(), head.topics(), questions);
  }

  /** The answer box saves itself as the learner types. Clearing it clears the answer. */
  @PutMapping("/api/projects/questions/{questionId}/answer")
  Answer answer(@PathVariable long questionId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody AnswerBody request) {
    projects.requireOwn(questionId, me.id());
    String answer = request == null || request.answer() == null ? "" : request.answer();
    if (answer.length() > MAX_ANSWER) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "answers are limited to " + MAX_ANSWER + " characters");
    }
    boolean blank = answer.isBlank();
    return jdbc.queryForObject(
        "update project_question set answer = :answer,"
            + " answered_at = case when :blank then null else now() end where id = :question"
            + " returning answer, answered_at",
        new MapSqlParameterSource().addValue("question", questionId)
            .addValue("answer", blank ? "" : answer).addValue("blank", blank),
        (rs, i) -> new Answer(rs.getString("answer"), rs.getObject("answered_at", OffsetDateTime.class)));
  }

  @PostMapping("/api/projects/questions/{questionId}/attempts")
  Progress rate(@PathVariable long questionId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody Rating request) {
    projects.requireOwn(questionId, me.id());
    if (request == null || !ReviewSchedule.RATINGS.contains(request.rating())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "rating must be one of " + ReviewSchedule.RATINGS);
    }
    jdbc.update(
        "insert into project_attempt (learner_id, question_id, rating) values (:learner, :question, :rating)",
        params(questionId, me).addValue("rating", request.rating()));
    return Progress.of(projects.rehearsals(me.id(), questionId));
  }

  /** Takes back the latest rating: a mis-click, or "not rehearsed after all". */
  @DeleteMapping("/api/projects/questions/{questionId}/attempts/latest")
  Progress undo(@PathVariable long questionId, @AuthenticationPrincipal LearnerPrincipal me) {
    projects.requireOwn(questionId, me.id());
    jdbc.update(
        "delete from project_attempt where id = (select id from project_attempt where learner_id = :learner"
            + " and question_id = :question order by created_at desc, id desc limit 1)",
        params(questionId, me));
    return Progress.of(projects.rehearsals(me.id(), questionId));
  }

  /** Project questions due for another rehearsal, for the home page beside the unit reviews. */
  @GetMapping("/api/projects/reviews")
  ReviewQueue reviews(@AuthenticationPrincipal LearnerPrincipal me) {
    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(me.id());
    if (rehearsals.isEmpty()) {
      return new ReviewQueue(List.of(), null);
    }
    LocalDate today = LocalDate.now(STUDY_ZONE);
    List<Due> due = new ArrayList<>();
    LocalDate[] next = {null};
    // Retired questions and retired projects drop out of the queue; their history stays.
    jdbc.query(
        "select q.id, q.project_id, p.name, q.rung, q.prompt, q.minutes from project_question q"
            + " join experience_project p on p.id = q.project_id"
            + " where p.learner_id = :learner and not p.retired and not q.retired",
        new MapSqlParameterSource("learner", me.id()),
        rs -> {
          List<Rehearsal> r = rehearsals.get(rs.getLong("id"));
          if (r == null) {
            return;
          }
          LocalDate dueOn = ProjectQueries.dueOn(r);
          if (!dueOn.isAfter(today)) {
            due.add(new Due(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                rs.getString("rung"), rs.getString("prompt"), rs.getInt("minutes"), dueOn));
          } else if (next[0] == null || dueOn.isBefore(next[0])) {
            next[0] = dueOn;
          }
        });
    due.sort(Comparator.comparing(Due::dueOn).thenComparing(Due::projectName).thenComparing(Due::questionId));
    return new ReviewQueue(due, next[0]);
  }

  static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }

  private static MapSqlParameterSource params(long questionId, LearnerPrincipal me) {
    return new MapSqlParameterSource().addValue("learner", me.id()).addValue("question", questionId);
  }
}
