package com.interviewprep.practice;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Run (the visible cases) and Submit (every case) through the code runner (runner design §2). The code is
 * saved first, so a runner failure never loses it. Hidden cases reach the page only as a count. A run is
 * never an attempt.
 */
@RestController
class PracticeController {

  private static final Logger LOG = System.getLogger(PracticeController.class.getName());

  private final CodeDraftController.CodeDrafts drafts;
  private final RunnerClient runner;
  private final CurriculumQueries curriculum;
  private final RunLimiter limiter;
  private final JsonMapper json;

  PracticeController(CodeDraftController.CodeDrafts drafts, RunnerClient runner, CurriculumQueries curriculum,
      RunLimiter limiter, JsonMapper json) {
    this.drafts = drafts;
    this.runner = runner;
    this.curriculum = curriculum;
    this.limiter = limiter;
    this.json = json;
  }

  record RunBody(String code, boolean submit) {}

  record CaseView(String name, String input, String expected, String verdict, String got, String error, Long ms) {}

  record Hidden(int passed, int total) {}

  record RunView(String status, String compileErrors, List<CaseView> cases, Hidden hidden, String output) {}

  @PostMapping("/api/units/{unitId}/run")
  ResponseEntity<?> run(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody RunBody request) {
    if (!curriculum.isVisible(unitId, me.slug())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    boolean submit = request != null && request.submit();
    String code = request == null ? null : request.code();
    drafts.save(me.id(), unitId, code); // 400 for code over 32 KB, before anything runs
    Optional<CurriculumQueries.CodingFixture> fixture = curriculum.codingFixture(unitId);
    if (fixture.isEmpty()) {
      return status(HttpStatus.CONFLICT, "not-ready");
    }
    String refused = limiter.acquire(me.id());
    if (refused != null) {
      return status(HttpStatus.TOO_MANY_REQUESTS, refused);
    }
    // A refusal returns before the try, so its finally never frees another request's slot.
    long start = System.nanoTime();
    try {
      List<CurriculumQueries.RunnerCase> cases = new ArrayList<>();
      Map<String, CurriculumQueries.RunnerCase> byId = new LinkedHashMap<>(); // wire order = curriculum order
      List<CurriculumQueries.RunnerCase> all = fixture.get().cases();
      for (int i = 0; i < all.size(); i++) {
        CurriculumQueries.RunnerCase c = all.get(i);
        if (submit || !c.hidden()) {
          cases.add(c);
          byId.put("case-" + i, c);
        }
      }
      ObjectNode body = json.createObjectNode();
      body.put("source", code == null ? "" : code);
      body.set("signature", json.readTree(fixture.get().signatureJson()));
      body.put("compare", fixture.get().compare());
      ArrayNode wire = body.putArray("cases");
      byId.forEach((id, c) -> {
        ObjectNode w = wire.addObject();
        w.put("id", id);
        w.set("args", json.readTree(c.argsJson()));
        w.set("expected", json.readTree(c.expectedJson()));
        w.put("reveal", !c.hidden());
      });
      RunnerClient.Outcome outcome = runner.run(body);
      return switch (outcome) {
        case RunnerClient.Busy b -> status(HttpStatus.SERVICE_UNAVAILABLE, "busy");
        case RunnerClient.Unavailable u -> status(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        case RunnerClient.Answered a when a.status() != 200 -> status(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        case RunnerClient.Answered a -> {
          RunView view = view(a.body(), byId, all, submit);
          if (submit) {
            int passed = view.cases().stream().filter(c -> c.verdict().equals("pass")).mapToInt(c -> 1).sum()
                + (view.hidden() == null ? 0 : view.hidden().passed());
            drafts.recordSubmit(me.id(), unitId, passed, cases.size());
          }
          LOG.log(Level.INFO, "practice run learner={0} unit={1} mode={2} status={3} cases={4} ms={5}",
              String.valueOf(me.id()), unitId, submit ? "submit" : "run", view.status(),
              String.valueOf(cases.size()), String.valueOf((System.nanoTime() - start) / 1_000_000));
          yield ResponseEntity.ok(view);
        }
      };
    } finally {
      limiter.release(me.id());
    }
  }

  /** Visible cases in curriculum order with their display strings; hidden ones only as a count. */
  private static RunView view(JsonNode answer, Map<String, CurriculumQueries.RunnerCase> byId,
      List<CurriculumQueries.RunnerCase> all, boolean submit) {
    Map<String, JsonNode> results = new HashMap<>();
    for (JsonNode r : answer.path("cases")) {
      results.put(r.path("id").asString(), r);
    }
    List<CaseView> visible = new ArrayList<>();
    int hiddenPassed = 0;
    int hiddenTotal = 0;
    for (int i = 0; i < all.size(); i++) {
      String id = "case-" + i;
      CurriculumQueries.RunnerCase c = byId.get(id);
      if (c == null) {
        continue;
      }
      JsonNode r = results.get(id);
      String verdict = r == null ? "not-run" : r.path("verdict").asString();
      if (c.hidden()) {
        hiddenTotal++;
        if (verdict.equals("pass")) {
          hiddenPassed++;
        }
        continue; // never its input, expected value, result, error or timing
      }
      visible.add(new CaseView(c.name(), c.input(), c.expected(), verdict, text(r, "got"), text(r, "error"),
          r == null || r.path("ms").isNull() || r.path("ms").isMissingNode() ? null : r.path("ms").asLong()));
    }
    boolean anyHidden = hiddenTotal > 0;
    return new RunView(answer.path("status").asString(), text(answer, "compileErrors"), visible,
        submit ? new Hidden(hiddenPassed, hiddenTotal) : null, anyHidden ? null : text(answer, "output"));
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.path(field);
    return v.isNull() || v.isMissingNode() ? null : v.asString();
  }

  private static ResponseEntity<Map<String, String>> status(HttpStatus status, String value) {
    return ResponseEntity.status(status).body(Map.of("status", value));
  }
}
