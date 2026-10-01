package com.interviewprep.projects;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adding, changing, hiding and reordering projects and questions in the app, without a file.
 *
 * <p>The rules match the import's: the same limits per field ({@link ProjectFields}), unknown units
 * dropped from the links and reported, and removing means hiding, never deleting, so an answer and
 * its ratings are never lost and a hidden item can be restored. A row added here gets a key of its
 * own, so the export and a later re-import of that export match it like any other.
 */
@RestController
class ProjectEditController {

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final CurriculumQueries curriculum;
  private final JsonMapper json;

  ProjectEditController(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction,
      CurriculumQueries curriculum, JsonMapper json) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.curriculum = curriculum;
    this.json = json;
  }

  record ProjectBody(String name, String summary) {}

  record QuestionBody(String rung, String prompt, String probes, String strongAnswer, List<String> units,
      Integer minutes) {}

  record Order(List<Long> questionIds) {}

  /** What was saved. Unit ids that are not in the curriculum were left out of the links. */
  record Saved(long id, List<String> unknownUnits) {}

  record Problems(List<String> problems) {}

  record HiddenProject(long id, String name) {}

  @PostMapping("/api/projects")
  ResponseEntity<?> addProject(@RequestBody ProjectBody body, @AuthenticationPrincipal LearnerPrincipal me) {
    List<String> problems = new ArrayList<>();
    String name = ProjectFields.text(body == null ? null : body.name(), "The name", ProjectFields.MAX_NAME, true,
        problems);
    String summary = ProjectFields.text(body == null ? null : body.summary(), "The summary",
        ProjectFields.MAX_SUMMARY, false, problems);
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("learner", me.id())
        .addValue("name", name).addValue("summary", summary).addValue("key", newKey());
    Integer count = jdbc.queryForObject(
        "select count(*) from experience_project where learner_id = :learner and not retired", params, Integer.class);
    if (count != null && count >= ProjectFields.MAX_PROJECTS) {
      problems.add("At most " + ProjectFields.MAX_PROJECTS + " projects; hide one first.");
    }
    if (!problems.isEmpty()) {
      return refused(problems);
    }
    long id = jdbc.queryForObject(
        "insert into experience_project (learner_id, key, name, summary, sort_order, added_in_app)"
            + " values (:learner, :key, :name, :summary, (select coalesce(max(sort_order), -1) + 1"
            + " from experience_project where learner_id = :learner), true) returning id",
        params, Long.class);
    return ResponseEntity.ok(new Saved(id, List.of()));
  }

  @PutMapping("/api/projects/{projectId}")
  ResponseEntity<?> changeProject(@PathVariable long projectId, @RequestBody ProjectBody body,
      @AuthenticationPrincipal LearnerPrincipal me) {
    requireProject(projectId, me, false);
    List<String> problems = new ArrayList<>();
    String name = ProjectFields.text(body == null ? null : body.name(), "The name", ProjectFields.MAX_NAME, true,
        problems);
    String summary = ProjectFields.text(body == null ? null : body.summary(), "The summary",
        ProjectFields.MAX_SUMMARY, false, problems);
    if (!problems.isEmpty()) {
      return refused(problems);
    }
    jdbc.update("update experience_project set name = :name, summary = :summary, updated_at = now()"
            + " where id = :project",
        new MapSqlParameterSource().addValue("project", projectId).addValue("name", name).addValue("summary", summary));
    return ResponseEntity.ok(new Saved(projectId, List.of()));
  }

  /** Hides the project with its questions; answers and ratings stay, and it can be restored. */
  @DeleteMapping("/api/projects/{projectId}")
  ResponseEntity<Void> hideProject(@PathVariable long projectId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireProject(projectId, me, false);
    jdbc.update("update experience_project set retired = true, updated_at = now() where id = :project",
        new MapSqlParameterSource("project", projectId));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/projects/{projectId}/restore")
  ResponseEntity<Void> restoreProject(@PathVariable long projectId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireProject(projectId, me, true);
    jdbc.update("update experience_project set retired = false, updated_at = now() where id = :project",
        new MapSqlParameterSource("project", projectId));
    return ResponseEntity.noContent().build();
  }

  /** Projects hidden by the learner or by an import, so they can be brought back. */
  @GetMapping("/api/projects/hidden")
  List<HiddenProject> hiddenProjects(@AuthenticationPrincipal LearnerPrincipal me) {
    return jdbc.query(
        "select id, name from experience_project where learner_id = :learner and retired order by sort_order, id",
        new MapSqlParameterSource("learner", me.id()),
        (rs, i) -> new HiddenProject(rs.getLong("id"), rs.getString("name")));
  }

  @PostMapping("/api/projects/{projectId}/questions")
  ResponseEntity<?> addQuestion(@PathVariable long projectId, @RequestBody QuestionBody body,
      @AuthenticationPrincipal LearnerPrincipal me) {
    requireProject(projectId, me, false);
    List<String> problems = new ArrayList<>();
    Checked q = check(body, me, problems);
    Integer count = jdbc.queryForObject(
        "select count(*) from project_question where project_id = :project and not retired",
        new MapSqlParameterSource("project", projectId), Integer.class);
    if (count != null && count >= ProjectFields.MAX_QUESTIONS) {
      problems.add("At most " + ProjectFields.MAX_QUESTIONS + " questions in a project; hide one first.");
    }
    if (!problems.isEmpty()) {
      return refused(problems);
    }
    long id = jdbc.queryForObject(
        "insert into project_question (project_id, key, rung, prompt, probes, strong_answer, unit_ids, minutes,"
            + " sort_order, added_in_app) values (:project, :key, :rung, :prompt, :probes, :strong,"
            + " array(select jsonb_array_elements_text(cast(:units as jsonb))), :minutes, (select coalesce(max(sort_order), -1) + 1 from project_question"
            + " where project_id = :project), true) returning id",
        q.params().addValue("project", projectId).addValue("key", newKey()), Long.class);
    return ResponseEntity.ok(new Saved(id, q.unknown()));
  }

  /** Changes the question's wording, rung, links or length. The answer and ratings are not touched. */
  @PutMapping("/api/projects/questions/{questionId}")
  ResponseEntity<?> changeQuestion(@PathVariable long questionId, @RequestBody QuestionBody body,
      @AuthenticationPrincipal LearnerPrincipal me) {
    requireQuestion(questionId, me, false);
    List<String> problems = new ArrayList<>();
    Checked q = check(body, me, problems);
    if (!problems.isEmpty()) {
      return refused(problems);
    }
    jdbc.update(
        "update project_question set rung = :rung, prompt = :prompt, probes = :probes, strong_answer = :strong,"
            + " unit_ids = array(select jsonb_array_elements_text(cast(:units as jsonb))), minutes = :minutes, updated_at = now() where id = :question",
        q.params().addValue("question", questionId));
    return ResponseEntity.ok(new Saved(questionId, q.unknown()));
  }

  /** Hides the question; its answer and ratings stay, and it can be restored from the project page. */
  @DeleteMapping("/api/projects/questions/{questionId}")
  ResponseEntity<Void> hideQuestion(@PathVariable long questionId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireQuestion(questionId, me, false);
    jdbc.update("update project_question set retired = true, updated_at = now() where id = :question",
        new MapSqlParameterSource("question", questionId));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/projects/questions/{questionId}/restore")
  ResponseEntity<Void> restoreQuestion(@PathVariable long questionId,
      @AuthenticationPrincipal LearnerPrincipal me) {
    requireQuestion(questionId, me, true);
    jdbc.update("update project_question set retired = false, updated_at = now() where id = :question",
        new MapSqlParameterSource("question", questionId));
    return ResponseEntity.noContent().build();
  }

  /**
   * Puts the project's questions in the order given. The list must be exactly the questions the page
   * shows, so a page that is out of date is refused rather than half applied.
   */
  @PutMapping("/api/projects/{projectId}/order")
  ResponseEntity<?> reorder(@PathVariable long projectId, @RequestBody Order body,
      @AuthenticationPrincipal LearnerPrincipal me) {
    requireProject(projectId, me, false);
    List<Long> wanted = body == null || body.questionIds() == null ? List.of() : body.questionIds();
    Set<Long> shown = new HashSet<>(jdbc.queryForList(
        "select id from project_question where project_id = :project and not retired",
        new MapSqlParameterSource("project", projectId), Long.class));
    if (wanted.size() != shown.size() || !shown.equals(new HashSet<>(wanted))) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(new Problems(List.of("The questions have changed since the page loaded; reload it and try again.")));
    }
    transaction.executeWithoutResult(status -> {
      for (int i = 0; i < wanted.size(); i++) {
        jdbc.update("update project_question set sort_order = :order where id = :question",
            new MapSqlParameterSource().addValue("order", i).addValue("question", wanted.get(i)));
      }
    });
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------

  private record Checked(MapSqlParameterSource params, List<String> unknown) {}

  private Checked check(QuestionBody body, LearnerPrincipal me, List<String> problems) {
    QuestionBody b = body == null ? new QuestionBody(null, null, null, null, null, null) : body;
    if (!ProjectFields.RUNGS.contains(b.rung())) {
      problems.add("The angle must be one of walkthrough, why, scale, failure, change, story.");
    }
    String prompt = ProjectFields.text(b.prompt(), "The question", ProjectFields.MAX_PROMPT, true, problems);
    String probes = ProjectFields.text(b.probes(), "The follow-ups", ProjectFields.MAX_PROBES, false, problems);
    String strong = ProjectFields.text(b.strongAnswer(), "What a strong answer covers",
        ProjectFields.MAX_STRONG_ANSWER, false, problems);
    List<String> units = ProjectFields.ids(b.units(), "The units", problems);
    int minutes = b.minutes() == null ? 15 : b.minutes();
    if (minutes < 1 || minutes > 120) {
      problems.add("The minutes must be a whole number from 1 to 120.");
    }
    // Links to units this learner cannot see, or that are not in the curriculum, are left out and
    // reported, as the import does.
    Set<String> known = new HashSet<>();
    curriculum.summaries(new HashSet<>(units), me.slug()).forEach(u -> known.add(u.id()));
    List<String> kept = units.stream().filter(known::contains).toList();
    List<String> unknown = List.copyOf(new TreeSet<>(units.stream().filter(u -> !known.contains(u)).toList()));
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("rung", b.rung()).addValue("prompt", prompt)
        .addValue("probes", probes).addValue("strong", strong).addValue("minutes", minutes)
        .addValue("units", json.writeValueAsString(kept));
    return new Checked(params, unknown);
  }

  /** The other learner's project is a 404, the same as one that does not exist. */
  private void requireProject(long projectId, LearnerPrincipal me, boolean hidden) {
    Boolean own = jdbc.queryForObject(
        "select exists(select 1 from experience_project where id = :project and learner_id = :learner"
            + " and retired = :hidden)",
        new MapSqlParameterSource().addValue("project", projectId).addValue("learner", me.id())
            .addValue("hidden", hidden),
        Boolean.class);
    if (!Boolean.TRUE.equals(own)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** A question in one of this learner's shown projects, itself shown or (to restore it) hidden. */
  private void requireQuestion(long questionId, LearnerPrincipal me, boolean hidden) {
    Boolean own = jdbc.queryForObject(
        "select exists(select 1 from project_question q join experience_project p on p.id = q.project_id"
            + " where q.id = :question and p.learner_id = :learner and not p.retired and q.retired = :hidden)",
        new MapSqlParameterSource().addValue("question", questionId).addValue("learner", me.id())
            .addValue("hidden", hidden),
        Boolean.class);
    if (!Boolean.TRUE.equals(own)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** A key the import format accepts and no file will have written: "app-" and 12 random hex digits. */
  private static String newKey() {
    return "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static ResponseEntity<Problems> refused(List<String> problems) {
    return ResponseEntity.unprocessableContent().body(new Problems(problems));
  }
}
