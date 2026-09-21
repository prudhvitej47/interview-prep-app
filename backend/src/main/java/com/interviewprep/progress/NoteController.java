package com.interviewprep.progress;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One private note per learner per unit.
 *
 * <p>This is where each learner's answers to a project deep-dive live — their own systems, which
 * never go into the content repository. It also serves any unit as a scratchpad.
 */
@RestController
@RequestMapping("/api/units/{unitId}/note")
class NoteController {

  // Generous for an answer outline or a page of working, and a stop for anything pasted by mistake.
  static final int MAX_LENGTH = 20_000;

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;

  NoteController(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
  }

  record Note(String body, OffsetDateTime updatedAt) {}

  record NoteBody(String body) {}

  @GetMapping
  Note read(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireVisible(unitId, me);
    return jdbc.query(
            "select body, updated_at from note where learner_id = :learner and unit_id = :unit",
            params(unitId, me),
            (rs, i) -> new Note(rs.getString("body"), rs.getObject("updated_at", OffsetDateTime.class)))
        .stream().findFirst().orElse(new Note("", null));
  }

  @PutMapping
  Note save(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody NoteBody request) {
    requireVisible(unitId, me);
    String body = request == null || request.body() == null ? "" : request.body();
    if (body.length() > MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "notes are limited to " + MAX_LENGTH + " characters");
    }
    // Clearing a note removes it rather than keeping an empty row around.
    if (body.isBlank()) {
      jdbc.update("delete from note where learner_id = :learner and unit_id = :unit", params(unitId, me));
      return new Note("", null);
    }
    return jdbc.queryForObject(
        "insert into note (learner_id, unit_id, body) values (:learner, :unit, :body)"
            + " on conflict (learner_id, unit_id) do update set body = excluded.body, updated_at = now()"
            + " returning body, updated_at",
        params(unitId, me).addValue("body", body),
        (rs, i) -> new Note(rs.getString("body"), rs.getObject("updated_at", OffsetDateTime.class)));
  }

  // Someone else's private unit is a 404 here too, so notes cannot be used to probe for it.
  private void requireVisible(String unitId, LearnerPrincipal me) {
    if (!curriculum.isVisible(unitId, me.slug())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static MapSqlParameterSource params(String unitId, LearnerPrincipal me) {
    return new MapSqlParameterSource().addValue("learner", me.id()).addValue("unit", unitId);
  }
}
