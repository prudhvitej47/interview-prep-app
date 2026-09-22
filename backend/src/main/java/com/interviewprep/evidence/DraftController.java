package com.interviewprep.evidence;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.progress.ProgressQueries;
import java.time.LocalDate;
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
 * A learner's debriefs of their own interviews: saved as drafts in Postgres, each learner's alone,
 * then sent to the curriculum as a finished evidence record (proposal D4). Sending is the only way
 * any of it leaves the app, and it never carries the learner's name or private notes.
 *
 * <p>Interviews someone else wrote up go in as articles instead ({@link ArticleController}): raw
 * text that an ingest run structures, rather than a second form to fill in by hand.
 */
@RestController
class DraftController {

  static final int MAX_BODY = 50_000;
  private static final Set<String> OUTCOMES =
      Set.of("offer", "no-offer", "downlevelled", "declined", "unknown", "not-applicable");

  private final NamedParameterJdbcTemplate jdbc;
  private final JsonMapper json;
  private final CurriculumQueries curriculum;
  private final ContentRepo contentRepo;

  DraftController(NamedParameterJdbcTemplate jdbc, JsonMapper json, CurriculumQueries curriculum,
      ContentRepo contentRepo) {
    this.jdbc = jdbc;
    this.json = json;
    this.curriculum = curriculum;
    this.contentRepo = contentRepo;
  }

  record Draft(long id, String kind, JsonNode body, OffsetDateTime createdAt, OffsetDateTime updatedAt,
      OffsetDateTime sentAt, String sentBranch, String sentUrl) {}

  record DraftRequest(String kind, JsonNode body) {}

  record Export(String path, String yaml) {}

  /** Everything that stops a draft exporting, in words the learner can act on. */
  record Problems(List<String> problems) {}

  record Sent(String branch, String url) {}

  /** The evidence record built from a draft, or what stops it being built. */
  private record Built(List<String> problems, String evidenceId, String path, String yaml,
      String company, int rounds, List<String> topics) {}

  @GetMapping("/api/evidence/drafts")
  List<Draft> mine(@AuthenticationPrincipal LearnerPrincipal me) {
    return jdbc.query("select id, kind, body::text, created_at, updated_at, sent_at, sent_branch"
        + " from evidence_draft where learner_id = :learner order by updated_at desc",
        new MapSqlParameterSource("learner", me.id()), (rs, i) -> draft(rs));
  }

  @GetMapping("/api/evidence/drafts/{id}")
  Draft one(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    return find(id, me);
  }

  /** Drafts may be incomplete; completeness is checked when exporting. */
  @PostMapping("/api/evidence/drafts")
  Draft create(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody DraftRequest request) {
    if (request == null || !"debrief".equals(request.kind())) {
      throw bad("kind must be debrief");
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
    if (find(id, me).sentAt() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "sent drafts are changed in their pull request");
    }
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
   * Problems come back as one list, so the learner can fix them all at once. Also offered as a
   * download, for when sending is not set up.
   */
  @GetMapping("/api/evidence/drafts/{id}/export")
  ResponseEntity<?> export(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    Built built = build(find(id, me));
    return built.problems().isEmpty() ? ResponseEntity.ok(new Export(built.path(), built.yaml()))
        : ResponseEntity.unprocessableContent().body(new Problems(built.problems()));
  }

  /**
   * Sends the draft to the curriculum: a {@code proposals/} branch holding the evidence file and a
   * {@code changes/} summary, which the content repository turns into a pull request by itself.
   * A finished record needs no further processing, so it can be reviewed and merged as it is.
   */
  @PostMapping("/api/evidence/drafts/{id}/send")
  ResponseEntity<?> send(@PathVariable long id, @AuthenticationPrincipal LearnerPrincipal me) {
    Draft draft = find(id, me);
    if (draft.sentAt() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "already sent");
    }
    if (!contentRepo.configured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sending is not set up");
    }
    Built built = build(draft);
    if (!built.problems().isEmpty()) {
      return ResponseEntity.unprocessableContent().body(new Problems(built.problems()));
    }
    String name = LocalDate.now(ProgressQueries.STUDY_ZONE) + "-" + built.evidenceId().substring(3);
    String branch;
    try {
      branch = contentRepo.send("proposals/" + name, Map.of(
              built.path(), built.yaml(),
              "changes/" + name + ".md", summary(built)),
          "Add a debrief of a " + companyName(built.company()) + " interview");
    } catch (ContentRepo.Failed e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
    jdbc.update("update evidence_draft set sent_at = now(), sent_branch = :branch where id = :id",
        new MapSqlParameterSource().addValue("branch", branch).addValue("id", id));
    return ResponseEntity.ok(new Sent(branch, contentRepo.pullRequestsFor(branch)));
  }

  /** The proposal's summary: it becomes the pull request's description (changes/README.md). */
  private String summary(Built built) {
    return "# A first-hand debrief: " + companyName(built.company()) + "\n\n"
        + "**Sources:** `" + built.evidenceId() + "`, sent from the app\n"
        + "**Learners affected:** both\n\n"
        + "## What changed\n"
        + "- New evidence: `" + built.path() + "`, " + built.rounds() + " round(s)"
        + (built.topics().isEmpty() ? "" : ", questions mapped to " + String.join(", ", built.topics())) + ".\n\n"
        + "## Why\n"
        + "Adds to how often these topics come up in interviews. It changes no weights: those move only on"
        + " recurring evidence. An ingest run can add units for any topic here that has none yet.\n\n"
        + "## Suggested placement\n"
        + "No new units in this proposal.\n";
  }

  private Built build(Draft draft) {
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
    String accessed = draft.createdAt().toLocalDate().toString();
    source.put("kind", "first-hand");
    source.put("title", "Debrief of a " + companyName(company) + " interview"
        + (text(b, "level") == null ? "" : " (" + text(b, "level") + ")"));
    source.put("accessed", accessed);

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
      return new Built(problems, null, null, null, company, rounds.size(), List.of());
    }

    String month = date != null && date.length() >= 7 ? date.substring(0, 7) : accessed.substring(0, 7);
    String suffix = (text(b, "level") == null ? "" : slug(text(b, "level")) + "-") + "debrief";
    String evidenceId = uniqueId("ev-" + month + "-" + company + "-" + suffix);

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", evidenceId);
    record.put("company", company);
    putIfPresent(record, "role", text(b, "role"));
    putIfPresent(record, "level", text(b, "level"));
    putIfPresent(record, "location", text(b, "location"));
    putIfPresent(record, "interview_date", date);
    record.put("source", source);
    record.put("tier", "primary");
    record.put("rounds", rounds);
    putIfPresent(record, "outcome", outcome);
    record.put("state", "proposed");

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndicatorIndent(0);
    options.setWidth(100);
    String year = date == null ? "undated" : date.substring(0, 4);
    List<String> allTopics = new ArrayList<>();
    for (Map<String, Object> round : rounds) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> qs = (List<Map<String, Object>>) round.getOrDefault("questions", List.of());
      for (Map<String, Object> question : qs) {
        @SuppressWarnings("unchecked")
        List<String> ts = (List<String>) question.get("topics");
        ts.stream().filter(t -> !allTopics.contains(t)).forEach(allTopics::add);
      }
    }
    return new Built(List.of(), evidenceId, "evidence/" + year + "/" + evidenceId + ".yaml",
        new Yaml(options).dump(record), company, rounds.size(), allTopics);
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
    return jdbc.query("select id, kind, body::text, created_at, updated_at, sent_at, sent_branch"
            + " from evidence_draft where id = :id and learner_id = :learner",
        new MapSqlParameterSource().addValue("id", id).addValue("learner", me.id()),
        (rs, i) -> draft(rs)).stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Draft draft(java.sql.ResultSet rs) throws java.sql.SQLException {
    String branch = rs.getString(7);
    return new Draft(rs.getLong(1), rs.getString(2), json.readTree(rs.getString(3)),
        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
        rs.getObject(6, OffsetDateTime.class), branch,
        branch == null ? null : contentRepo.pullRequestsFor(branch));
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
