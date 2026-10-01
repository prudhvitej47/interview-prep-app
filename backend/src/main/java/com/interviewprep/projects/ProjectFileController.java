package com.interviewprep.projects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.projects.ProjectQueries.Rehearsal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A learner's question file in and out. The file is theirs, kept outside Git. Questions can also be
 * added and changed in the app ({@link ProjectEditController}); the export carries both.
 *
 * <p>Importing again is safe: projects and questions are matched by their keys, so a reworded prompt
 * updates in place and keeps the answer and ratings, and a question missing from the new file is
 * retired (hidden), never deleted, unless the learner added it in the app: a file speaks only for what
 * it brought. Nothing here logs a request body: it is someone's career.
 */
@RestController
class ProjectFileController {

  static final int VERSION = 1;
  static final int MAX_BYTES = 1024 * 1024;
  static final int MAX_PROJECTS = ProjectFields.MAX_PROJECTS;
  static final int MAX_QUESTIONS = ProjectFields.MAX_QUESTIONS;
  static final Set<String> RUNGS = ProjectFields.RUNGS;

  // The caps per field are shared with editing in the app (ProjectFields).
  private static final int MAX_NAME = ProjectFields.MAX_NAME;
  private static final int MAX_SUMMARY = ProjectFields.MAX_SUMMARY;
  private static final int MAX_PROMPT = ProjectFields.MAX_PROMPT;
  private static final int MAX_PROBES = ProjectFields.MAX_PROBES;
  private static final int MAX_STRONG_ANSWER = ProjectFields.MAX_STRONG_ANSWER;
  private static final int MAX_LINKS = ProjectFields.MAX_LINKS;
  private static final int MAX_ID = ProjectFields.MAX_ID;
  private static final int MAX_PROBLEMS_SHOWN = 20;
  private static final Pattern KEY = ProjectFields.KEY;

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final JsonMapper json;
  private final CurriculumQueries curriculum;
  private final ProjectQueries projects;

  ProjectFileController(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, JsonMapper json,
      CurriculumQueries curriculum, ProjectQueries projects) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.json = json;
    this.curriculum = curriculum;
    this.projects = projects;
  }

  record QuestionIn(String key, String rung, String prompt, String probes, String strongAnswer,
      List<String> units, int minutes) {}

  record ProjectIn(String key, String name, String summary, List<String> topics, List<QuestionIn> questions) {}

  record Problems(List<String> problems) {}

  /** What an import changed. Unknown units were dropped from the links, and the import went ahead. */
  record Imported(int projectsAdded, int projectsUpdated, int projectsRetired, int questionsAdded,
      int questionsUpdated, int questionsRetired, List<String> unknownUnits) {}

  // The export is the import format, plus what the learner has done with each question.
  record RatingOut(String rating, OffsetDateTime at) {}

  record QuestionOut(String key, String rung, String prompt, String probes,
      @JsonProperty("strong_answer") String strongAnswer, List<String> units, int minutes, String answer,
      @JsonProperty("answered_at") OffsetDateTime answeredAt, List<RatingOut> ratings) {}

  record ProjectOut(String key, String name, String summary, List<String> topics, List<QuestionOut> questions) {}

  record Export(int version, @JsonProperty("exported_at") OffsetDateTime exportedAt, List<ProjectOut> projects) {}

  /**
   * The body is read by hand, not bound: the size limit has to hold before it is all in memory, and a
   * parse error must not be echoed back or logged with a piece of the file in it.
   */
  @PostMapping("/api/projects/import")
  ResponseEntity<?> importFile(HttpServletRequest request, @AuthenticationPrincipal LearnerPrincipal me)
      throws IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      return tooLarge();
    }
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes(MAX_BYTES + 1);
    }
    if (body.length > MAX_BYTES) {
      return tooLarge();
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      return problems(List.of("The file is not valid JSON."));
    }
    List<String> problems = new ArrayList<>();
    List<ProjectIn> file = read(root, problems);
    if (!problems.isEmpty()) {
      return problems(problems);
    }
    return ResponseEntity.ok(transaction.execute(status -> apply(file, me)));
  }

  @GetMapping("/api/projects/export")
  ResponseEntity<Export> export(@AuthenticationPrincipal LearnerPrincipal me) {
    Map<Long, List<Rehearsal>> rehearsals = projects.rehearsals(me.id());
    Map<Long, ProjectOut> out = new LinkedHashMap<>();
    jdbc.query(
        "select id, key, name, summary, topic_ids from experience_project"
            + " where learner_id = :learner and not retired order by sort_order, id",
        new MapSqlParameterSource("learner", me.id()),
        rs -> {
          out.put(rs.getLong("id"), new ProjectOut(rs.getString("key"), rs.getString("name"),
              rs.getString("summary") == null ? "" : rs.getString("summary"),
              ProjectController.strings(rs.getArray("topic_ids")), new ArrayList<>()));
        });
    jdbc.query(
        "select q.id, q.project_id, q.key, q.rung, q.prompt, q.probes, q.strong_answer, q.unit_ids,"
            + " q.minutes, q.answer, q.answered_at from project_question q"
            + " join experience_project p on p.id = q.project_id"
            + " where p.learner_id = :learner and not p.retired and not q.retired"
            + " order by q.sort_order, q.id",
        new MapSqlParameterSource("learner", me.id()),
        rs -> {
          out.get(rs.getLong("project_id")).questions().add(new QuestionOut(rs.getString("key"),
              rs.getString("rung"), rs.getString("prompt"), rs.getString("probes"),
              rs.getString("strong_answer"), ProjectController.strings(rs.getArray("unit_ids")),
              rs.getInt("minutes"), rs.getString("answer"), rs.getObject("answered_at", OffsetDateTime.class),
              rehearsals.getOrDefault(rs.getLong("id"), List.of()).stream()
                  .map(r -> new RatingOut(r.rating(), r.at())).toList()));
        });
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-projects.json\"")
        .body(new Export(VERSION, OffsetDateTime.now(), List.copyOf(out.values())));
  }

  private Imported apply(List<ProjectIn> file, LearnerPrincipal me) {
    // Links to units this learner cannot see, or that have left the curriculum, are dropped and
    // reported: content moves on, and a stale link is no reason to refuse the file.
    Set<String> linked = new HashSet<>();
    file.forEach(p -> p.questions().forEach(q -> linked.addAll(q.units())));
    Set<String> known = new HashSet<>();
    curriculum.summaries(linked, me.slug()).forEach(u -> known.add(u.id()));
    Set<String> unknown = new TreeSet<>(linked);
    unknown.removeAll(known);

    int[] n = new int[6];
    List<String> projectKeys = new ArrayList<>();
    for (int i = 0; i < file.size(); i++) {
      ProjectIn p = file.get(i);
      projectKeys.add(p.key());
      MapSqlParameterSource params = new MapSqlParameterSource()
          .addValue("learner", me.id()).addValue("key", p.key()).addValue("name", p.name())
          .addValue("summary", p.summary()).addValue("topics", toJson(p.topics())).addValue("order", i);
      // "is distinct from" keeps an unchanged row unchanged, so it can be told apart from an update.
      Upserted project = upsert(
          "insert into experience_project (learner_id, key, name, summary, topic_ids, sort_order)"
              + " values (:learner, :key, :name, :summary,"
              + " array(select jsonb_array_elements_text(cast(:topics as jsonb))), :order)"
              + " on conflict (learner_id, key) do update set name = excluded.name,"
              + " summary = excluded.summary, topic_ids = excluded.topic_ids,"
              + " sort_order = excluded.sort_order, retired = false, updated_at = now()"
              + " where (experience_project.name, experience_project.summary, experience_project.topic_ids,"
              + " experience_project.sort_order, experience_project.retired)"
              + " is distinct from (excluded.name, excluded.summary, excluded.topic_ids, excluded.sort_order, false)"
              + " returning id, xmax = 0 as inserted",
          "select id from experience_project where learner_id = :learner and key = :key", params);
      count(project, n, 0);

      List<String> questionKeys = new ArrayList<>();
      for (int j = 0; j < p.questions().size(); j++) {
        QuestionIn q = p.questions().get(j);
        questionKeys.add(q.key());
        MapSqlParameterSource qp = new MapSqlParameterSource()
            .addValue("project", project.id()).addValue("key", q.key()).addValue("rung", q.rung())
            .addValue("prompt", q.prompt()).addValue("probes", q.probes())
            .addValue("strong", q.strongAnswer()).addValue("minutes", q.minutes()).addValue("order", j)
            .addValue("units", toJson(q.units().stream().filter(known::contains).toList()));
        // The answer is not in this statement at all: an import can never overwrite one.
        count(upsert(
            "insert into project_question (project_id, key, rung, prompt, probes, strong_answer, unit_ids,"
                + " minutes, sort_order) values (:project, :key, :rung, :prompt, :probes, :strong,"
                + " array(select jsonb_array_elements_text(cast(:units as jsonb))), :minutes, :order)"
                + " on conflict (project_id, key) do update set rung = excluded.rung,"
                + " prompt = excluded.prompt, probes = excluded.probes, strong_answer = excluded.strong_answer,"
                + " unit_ids = excluded.unit_ids, minutes = excluded.minutes, sort_order = excluded.sort_order,"
                + " retired = false, updated_at = now()"
                + " where (project_question.rung, project_question.prompt, project_question.probes,"
                + " project_question.strong_answer, project_question.unit_ids, project_question.minutes,"
                + " project_question.sort_order, project_question.retired)"
                + " is distinct from (excluded.rung, excluded.prompt, excluded.probes, excluded.strong_answer,"
                + " excluded.unit_ids, excluded.minutes, excluded.sort_order, false)"
                + " returning id, xmax = 0 as inserted",
            "select id from project_question where project_id = :project and key = :key", qp), n, 3);
      }
      n[5] += jdbc.update(
          "update project_question set retired = true, updated_at = now() where project_id = :project"
              + " and not retired and not added_in_app and key not in (select jsonb_array_elements_text(cast(:keys as jsonb)))",
          new MapSqlParameterSource().addValue("project", project.id()).addValue("keys", toJson(questionKeys)));
    }
    // A whole project missing from the file is hidden the same way, with its questions and answers kept.
    n[2] = jdbc.update(
        "update experience_project set retired = true, updated_at = now() where learner_id = :learner"
            + " and not retired and not added_in_app and key not in (select jsonb_array_elements_text(cast(:keys as jsonb)))",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("keys", toJson(projectKeys)));
    return new Imported(n[0], n[1], n[2], n[3], n[4], n[5], List.copyOf(unknown));
  }

  private record Upserted(long id, Boolean inserted) {}

  /** Inserted, updated, or (no row back) unchanged; in the last case the id is looked up. */
  private Upserted upsert(String sql, String lookup, MapSqlParameterSource params) {
    return jdbc.query(sql, params, (rs, i) -> new Upserted(rs.getLong("id"), rs.getBoolean("inserted")))
        .stream().findFirst()
        .orElseGet(() -> new Upserted(jdbc.queryForObject(lookup, params, Long.class), null));
  }

  private static void count(Upserted row, int[] n, int at) {
    if (row.inserted() == null) {
      return;
    }
    n[row.inserted() ? at : at + 1]++;
  }

  private String toJson(List<String> values) {
    return json.writeValueAsString(values);
  }

  // ---------------------------------------------------------------------------
  // Reading and checking the file. Every problem is collected, so one round of fixes is enough.
  // ---------------------------------------------------------------------------

  private List<ProjectIn> read(JsonNode root, List<String> problems) {
    if (!root.isObject()) {
      problems.add("The file must be a JSON object with \"version\" and \"projects\".");
      return List.of();
    }
    JsonNode version = root.path("version");
    if (!version.isIntegralNumber() || version.asInt() != VERSION) {
      problems.add("\"version\" must be " + VERSION + ".");
    }
    JsonNode list = root.path("projects");
    if (!list.isArray() || list.isEmpty()) {
      // An empty list would retire everything, which is never what a file means.
      problems.add("\"projects\" must be a list with at least one project.");
      return List.of();
    }
    if (list.size() > MAX_PROJECTS) {
      problems.add("At most " + MAX_PROJECTS + " projects; this file has " + list.size() + ".");
      return List.of();
    }
    List<ProjectIn> out = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      out.add(readProject(list.get(i), i + 1, keys, problems));
    }
    if (problems.size() > MAX_PROBLEMS_SHOWN) {
      int more = problems.size() - MAX_PROBLEMS_SHOWN;
      problems.subList(MAX_PROBLEMS_SHOWN, problems.size()).clear();
      problems.add("…and " + more + " more.");
    }
    return out;
  }

  private ProjectIn readProject(JsonNode node, int n, Set<String> keys, List<String> problems) {
    String where = "Project " + n;
    if (!node.isObject()) {
      problems.add(where + " must be an object.");
      return null;
    }
    String key = key(node, where, keys, problems);
    if (key != null) {
      where = "Project \"" + key + "\"";
    }
    String name = text(node, "name", where, MAX_NAME, true, problems);
    String summary = text(node, "summary", where, MAX_SUMMARY, false, problems);
    List<String> topics = ids(node, "topics", where, problems);
    JsonNode list = node.path("questions");
    List<QuestionIn> questions = new ArrayList<>();
    if (!list.isMissingNode() && !list.isArray()) {
      problems.add(where + ": \"questions\" must be a list.");
    } else if (list.size() > MAX_QUESTIONS) {
      problems.add(where + ": at most " + MAX_QUESTIONS + " questions; it has " + list.size() + ".");
    } else {
      Set<String> questionKeys = new HashSet<>();
      for (int j = 0; j < list.size(); j++) {
        questions.add(readQuestion(list.get(j), where + ", question " + (j + 1), questionKeys, problems));
      }
    }
    return new ProjectIn(key, name, summary, topics, questions);
  }

  private QuestionIn readQuestion(JsonNode node, String where, Set<String> keys, List<String> problems) {
    if (!node.isObject()) {
      problems.add(where + " must be an object.");
      return null;
    }
    String key = key(node, where, keys, problems);
    if (key != null) {
      where = where + " (\"" + key + "\")";
    }
    String rung = node.path("rung").isString() ? node.path("rung").asString() : null;
    if (!RUNGS.contains(rung)) {
      problems.add(where + ": \"rung\" must be one of walkthrough, why, scale, failure, change, story.");
    }
    String prompt = text(node, "prompt", where, MAX_PROMPT, true, problems);
    String probes = text(node, "probes", where, MAX_PROBES, false, problems);
    String strong = text(node, "strong_answer", where, MAX_STRONG_ANSWER, false, problems);
    List<String> units = ids(node, "units", where, problems);
    int minutes = 15;
    JsonNode m = node.path("minutes");
    if (!m.isMissingNode() && !m.isNull()) {
      if (!m.isIntegralNumber() || m.asInt() < 1 || m.asInt() > 120) {
        problems.add(where + ": \"minutes\" must be a whole number from 1 to 120.");
      } else {
        minutes = m.asInt();
      }
    }
    return new QuestionIn(key, rung, prompt, probes, strong, units, minutes);
  }

  private static String key(JsonNode node, String where, Set<String> seen, List<String> problems) {
    JsonNode k = node.path("key");
    if (!k.isString() || !KEY.matcher(k.asString()).matches()) {
      problems.add(where + ": \"key\" must be 1 to 100 letters, digits, dots, dashes or underscores.");
      return null;
    }
    if (!seen.add(k.asString())) {
      problems.add(where + ": the key \"" + k.asString() + "\" is used twice.");
    }
    return k.asString();
  }

  private static String text(JsonNode node, String field, String where, int max, boolean required,
      List<String> problems) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      if (required) {
        problems.add(where + ": \"" + field + "\" is missing.");
      }
      return "";
    }
    if (!value.isString()) {
      problems.add(where + ": \"" + field + "\" must be text.");
      return "";
    }
    String s = value.asString().strip();
    if (required && s.isEmpty()) {
      problems.add(where + ": \"" + field + "\" is empty.");
    } else if (s.length() > max) {
      problems.add(where + ": \"" + field + "\" is longer than " + max + " characters.");
    }
    return s;
  }

  private static List<String> ids(JsonNode node, String field, String where, List<String> problems) {
    JsonNode list = node.path(field);
    if (list.isMissingNode() || list.isNull()) {
      return List.of();
    }
    if (!list.isArray() || list.size() > MAX_LINKS) {
      problems.add(where + ": \"" + field + "\" must be a list of at most " + MAX_LINKS + " ids.");
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      JsonNode id = list.get(i);
      if (!id.isString() || id.asString().isBlank() || id.asString().length() > MAX_ID) {
        problems.add(where + ": \"" + field + "\" must hold ids as text.");
        return List.of();
      }
      if (!out.contains(id.asString())) {
        out.add(id.asString());
      }
    }
    return out;
  }

  private static ResponseEntity<Problems> tooLarge() {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(new Problems(List.of("The file is larger than 1 MB.")));
  }

  private static ResponseEntity<Problems> problems(List<String> problems) {
    return ResponseEntity.unprocessableContent().body(new Problems(problems));
  }
}
