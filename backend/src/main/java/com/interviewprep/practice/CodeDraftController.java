package com.interviewprep.practice;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** A learner's code for a coding unit, saved like a note (runner design §2). */
@RestController
@RequestMapping("/api/units/{unitId}/code")
class CodeDraftController {

  private final CodeDrafts drafts;
  private final CurriculumQueries curriculum;
  private final JsonMapper json;

  CodeDraftController(CodeDrafts drafts, CurriculumQueries curriculum, JsonMapper json) {
    this.drafts = drafts;
    this.curriculum = curriculum;
    this.json = json;
  }

  record CodeBody(String code) {}

  /** The draft plus the stub to start from: null when this problem cannot run yet (no fixture loaded). */
  record CodeView(String code, OffsetDateTime savedAt, CodeDrafts.LastSubmit lastSubmit, String starter) {}

  @GetMapping
  CodeView read(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireVisible(unitId, me);
    return view(unitId, drafts.read(me.id(), unitId));
  }

  @PutMapping
  CodeView save(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody CodeBody request) {
    requireVisible(unitId, me);
    return view(unitId, drafts.save(me.id(), unitId, request == null ? null : request.code()));
  }

  private CodeView view(String unitId, CodeDrafts.Draft draft) {
    String starter = curriculum.codingFixture(unitId)
        .map(f -> Starter.of(json.readTree(f.signatureJson()))).orElse(null);
    return new CodeView(draft.code(), draft.savedAt(), draft.lastSubmit(), starter);
  }

  private void requireVisible(String unitId, LearnerPrincipal me) {
    if (!curriculum.isVisible(unitId, me.slug())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** The code_draft table. Shared with PracticeController, which saves before every run. */
  @Component
  static class CodeDrafts {

    static final int MAX_BYTES = 32 * 1024;

    record LastSubmit(int passed, int total, OffsetDateTime at) {}

    record Draft(String code, OffsetDateTime savedAt, LastSubmit lastSubmit) {}

    private final NamedParameterJdbcTemplate jdbc;

    CodeDrafts(NamedParameterJdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    Draft read(long learnerId, String unitId) {
      return jdbc.query("select code, saved_at, last_submit_passed, last_submit_total, last_submit_at"
              + " from code_draft where learner_id = :learner and unit_id = :unit",
          params(learnerId, unitId), (rs, i) -> new Draft(rs.getString("code"),
              rs.getObject("saved_at", OffsetDateTime.class),
              rs.getObject("last_submit_at") == null ? null : new LastSubmit(rs.getInt("last_submit_passed"),
                  rs.getInt("last_submit_total"), rs.getObject("last_submit_at", OffsetDateTime.class))))
          .stream().findFirst().orElse(new Draft("", null, null));
    }

    Draft save(long learnerId, String unitId, String code) {
      String text = code == null ? "" : code;
      if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is limited to 32 KB");
      }
      jdbc.update("insert into code_draft (learner_id, unit_id, code) values (:learner, :unit, :code)"
              + " on conflict (learner_id, unit_id) do update set code = excluded.code, saved_at = now()",
          params(learnerId, unitId).addValue("code", text));
      return read(learnerId, unitId);
    }

    void recordSubmit(long learnerId, String unitId, int passed, int total) {
      jdbc.update("update code_draft set last_submit_passed = :passed, last_submit_total = :total,"
              + " last_submit_at = now() where learner_id = :learner and unit_id = :unit",
          params(learnerId, unitId).addValue("passed", passed).addValue("total", total));
    }

    private static MapSqlParameterSource params(long learnerId, String unitId) {
      return new MapSqlParameterSource().addValue("learner", learnerId).addValue("unit", unitId);
    }
  }
}
