package com.interviewprep.evidence;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A learner's own debriefs and found reports: saved as drafts in Postgres, each learner's alone,
 * and exported as an evidence file for the content repository (proposal D4). The export is the
 * only way any of it leaves the app, and it never carries the learner's name or private notes.
 */
@RestController
class DraftController {

  static final int MAX_BODY = 50_000;
  private static final Set<String> REPORT_KINDS =
      Set.of("candidate-report", "official-guide", "prep-guide", "news", "curated-bank", "user-provided");
  private static final Set<String> OUTCOMES =
      Set.of("offer", "no-offer", "downlevelled", "declined", "unknown", "not-applicable");

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper json;
  private final CurriculumQueries curriculum;

  DraftController(NamedParameterJdbcTemplate jdbc, JsonMapper json, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.json = json;
    this.curriculum = curriculum;
  }

  record Draft(long id, String kind, JsonNode body, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  record DraftRequest(String kind, JsonNode body) {}

  record Export(String path, String yaml) {}

  /** Everything that stops a draft exporting, in words the learner can act on. */
  record Problems(List<String> problems) {}

  @GetMapping("/api/evidence/drafts")
  List<Draft> mine(@AuthenticationPrincipal LearnerPrincipal me) {
    return jdbc.query("select id, kind, body::text, created_at, updated_at from evidence_draft"
        + " where learner_id = :learner order by updated_at desc",
        new MapSqlParameterSource("learner", me.id()), (rs, i) -> draft(rs));
  }

  @GetMapping("/api/evidence/drafts/{id}")
  Draft one(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    return find(id, me);
  }

  /** Drafts may be incomplete; completeness is checked when exporting. */
  @PostMapping("/api/evidence/drafts")
  Draft create(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody DraftRequest request) {
    if (request == null || !Set.of("debrief", "report").contains(request.kind())) {
      throw bad("kind must be debrief or report");
    }
    long id = jdbc.queryForObject("insert into evidence_draft (learner_id, kind, body)"
        + " values (:learner, :kind, cast(:body as jsonb)) returning id",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("kind", request.kind())
            .addValue("body", bodyText(request)), Long.class);
    return find(id, me);
  }

  @PutMapping("/api/evidence/drafts/{id}")
  Draft save(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody DraftRequest request) {
    find(id, me);
    jdbc.update("update evidence_draft set body = cast(:body as jsonb), updated_at = now()"
        + " where id = :id and learner_id = :learner",
        new MapSqlParameterSource().addValue("id", id).addValue("learner", me.id())
            .addValue("body", bodyText(request)));
    return find(id, me);
  }

  @DeleteMapping("/api/evidence/drafts/{id}")
  void delete(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    jdbc.update("delete from evidence_draft where id = :id and learner_id = :learner",
        new MapSqlParameterSource().addValue("id", id).addValue("learner", me.id()));
  }

  /**
   * The draft as an evidence file that passes the content repository's validator: the same
   * required fields, known company, round types and topic ids, questions each mapped to a topic.
   * Problems come back as one list, so the learner can fix them all at once.
   */
  @GetMapping("/api/evidence/drafts/{id}/export")
  ResponseEntity<?> export(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    Draft draft = find(id, me);
    JsonNode b = draft.body();
    List<String> problems = new ArrayList<>();

    String company = text(b, "company");
    Set<String> companies = Set.copyOf(jdbc.queryForList("select id from company",
        new MapSqlParameterSource(), String.class));
    if (company == null || !companies.contains(company) || company.equals("general")) {
      problems.add("Choose the company.");
    }
    String date = text(b, "interview_date");
    if (date != null && !date.matches("\\d{4}(-\\d{2}){0,2}")) {
      problems.add("The interview date must look like 2026, 2026-10 or 2026-10-05.");
    }
    String outcome = text(b, "outcome");
    if (outcome != null && !OUTCOMES.contains(outcome)) {
      problems.add("Unknown outcome " + outcome + ".");
    }

    Map<String, Object> source = new LinkedHashMap<>();
    String tier;
    String accessed = draft.createdAt().toLocalDate().toString();
    if (draft.kind().equals("debrief")) {
      source.put("kind", "first-hand");
      source.put("title", "Debrief of a " + companyName(company) + " interview"
          + (text(b, "level") == null ? "" : " (" + text(b, "level") + ")"));
      source.put("accessed", accessed);
      tier = "primary";
    } else {
      JsonNode s = b.path("source");
      String kind = text(s, "kind");
      if (kind == null || !REPORT_KINDS.contains(kind)) {
        problems.add("Choose what kind of source the report is.");
      }
      if (text(s, "title") == null) {
        problems.add("Give the report's title.");
      }
      String url = text(s, "url");
      if (url != null && !url.matches("https?://\\S+")) {
        problems.add("The link must start with http:// or https://.");
      }
      source.put("kind", kind);
      source.put("title", text(s, "title"));
      putIfPresent(source, "url", url);
      putIfPresent(source, "publisher", text(s, "publisher"));
      source.put("accessed", accessed);
      tier = "official-guide".equals(kind) ? "primary" : "secondary";
    }

    Set<String> roundTypes = Set.copyOf(jdbc.queryForList("select id from round_type",
        new MapSqlParameterSource(), String.class));
    Set<String> topics = curriculum.topicPlaces().keySet();
    List<Map<String, Object>> rounds = new ArrayList<>();
    int n = 0;
    for (JsonNode r : b.path("rounds")) {
      n++;
      String type = text(r, "type");
      if (type == null || !roundTypes.contains(type)) {
        problems.add("Round " + n + ": choose its type.");
      }
      Map<String, Object> round = new LinkedHashMap<>();
      round.put("type", type);
      putIfPresent(round, "summary", text(r, "summary"));
      List<Map<String, Object>> questions = new ArrayList<>();
      int q = 0;
      for (JsonNode question : r.path("questions")) {
        q++;
        String textValue = text(question, "text");
        List<String> mapped = new ArrayList<>();
        question.path("topics").forEach(t -> mapped.add(t.asString()));
        if (textValue == null) {
          problems.add("Round " + n + ", question " + q + ": write the question, paraphrased.");
        }
        if (mapped.isEmpty()) {
          problems.add("Round " + n + ", question " + q + ": map it to at least one topic.");
        }
        for (String t : mapped) {
          if (!topics.contains(t)) {
            problems.add("Round " + n + ", question " + q + ": unknown topic " + t + ".");
          }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("text", textValue);
        item.put("topics", mapped);
        questions.add(item);
      }
      if (!questions.isEmpty()) {
        round.put("questions", questions);
      }
      rounds.add(round);
    }
    if (rounds.isEmpty()) {
      problems.add("Add at least one round.");
    }
    if (!problems.isEmpty()) {
      return ResponseEntity.unprocessableContent().body(new Problems(problems));
    }

    String month = date != null && date.length() >= 7 ? date.substring(0, 7) : accessed.substring(0, 7);
    String suffix = draft.kind().equals("debrief")
        ? (text(b, "level") == null ? "" : slug(text(b, "level")) + "-") + "debrief"
        : slug(text(b.path("source"), "publisher") == null ? "report" : text(b.path("source"), "publisher"));
    String evidenceId = uniqueId("ev-" + month + "-" + company + "-" + suffix);

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", evidenceId);
    record.put("company", company);
    putIfPresent(record, "role", text(b, "role"));
    putIfPresent(record, "level", text(b, "level"));
    putIfPresent(record, "location", text(b, "location"));
    putIfPresent(record, "interview_date", date);
    record.put("source", source);
    record.put("tier", tier);
    record.put("rounds", rounds);
    putIfPresent(record, "outcome", outcome);
    record.put("state", "proposed");

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndicatorIndent(0);
    options.setWidth(100);
    String year = date == null ? "undated" : date.substring(0, 4);
    return ResponseEntity.ok(
        new Export("evidence/" + year + "/" + evidenceId + ".yaml", new Yaml(options).dump(record)));
  }

  private String uniqueId(String base) {
    String id = base;
    for (int i = 2; Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from evidence where id = :id)", new MapSqlParameterSource("id", id),
        Boolean.class)); i++) {
      id = base + "-" + i;
    }
    return id;
  }

  private String companyName(String id) {
    List<String> names = jdbc.queryForList("select name from company where id = :id",
        new MapSqlParameterSource("id", id == null ? "" : id), String.class);
    return names.isEmpty() ? "company" : names.getFirst();
  }

  static String slug(String s) {
    return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }

  private Draft find(long id, LearnerPrincipal me) {
    // Someone else's draft is a 404, the same as one that does not exist.
    return jdbc.query("select id, kind, body::text, created_at, updated_at from evidence_draft"
            + " where id = :id and learner_id = :learner",
        new MapSqlParameterSource().addValue("id", id).addValue("learner", me.id()),
        (rs, i) -> draft(rs)).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Draft draft(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Draft(rs.getLong(1), rs.getString(2), json.readTree(rs.getString(3)),
        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class));
  }

  private String bodyText(DraftRequest request) {
    JsonNode body = request == null || request.body() == null || !request.body().isObject()
        ? json.createObjectNode() : request.body();
    String text = json.writeValueAsString(body);
    if (text.length() > MAX_BODY) {
      throw bad("a draft is limited to " + MAX_BODY + " characters");
    }
    return text;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    String s = v.asString().trim();
    return s.isEmpty() ? null : s;
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
