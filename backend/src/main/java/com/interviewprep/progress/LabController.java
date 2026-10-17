package com.interviewprep.progress;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A learner's laptop lab: whether it is done, and checking the token LabCheck printed.
 *
 * <p>"Lab done" is its own row, never an attempt: only the explicit rating marks a unit done,
 * schedules reviews or earns stars, so a lab changes none of those.
 */
@RestController
@RequestMapping("/api/units/{unitId}/lab")
class LabController {

  private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}");

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;

  LabController(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
  }

  record LabStatus(OffsetDateTime doneAt) {}

  record TokenBody(String token) {}

  record CheckResult(boolean right, OffsetDateTime doneAt) {}

  @GetMapping
  LabStatus status(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me) {
    requireLab(unitId, me);
    return new LabStatus(doneAt(unitId, me));
  }

  @PostMapping("/check")
  CheckResult check(@PathVariable String unitId, @AuthenticationPrincipal LearnerPrincipal me,
      @RequestBody TokenBody request) {
    requireLab(unitId, me);
    String token = normalise(request == null ? null : request.token());
    if (!TOKEN.matcher(token).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a token is 8 hexadecimal characters");
    }
    if (!curriculum.labTokenMatches(unitId, token)) {
      return new CheckResult(false, doneAt(unitId, me));
    }
    // The first proof keeps its date: checking again is harmless.
    jdbc.update("insert into lab_result (learner_id, unit_id) values (:learner, :unit) on conflict do nothing",
        params(unitId, me));
    return new CheckResult(true, doneAt(unitId, me));
  }

  /** Learners paste the whole line ("TOKEN: 70e1ab80"), in any case, with stray spaces. */
  static String normalise(String raw) {
    String token = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (token.startsWith("token:")) {
      token = token.substring("token:".length()).trim();
    }
    return token;
  }

  private OffsetDateTime doneAt(String unitId, LearnerPrincipal me) {
    return jdbc.query("select done_at from lab_result where learner_id = :learner and unit_id = :unit",
            params(unitId, me), (rs, i) -> rs.getObject("done_at", OffsetDateTime.class))
        .stream().findFirst().orElse(null);
  }

  // Someone else's private unit, or a unit without a lab, is a 404, as for notes.
  private void requireLab(String unitId, LearnerPrincipal me) {
    if (!curriculum.isVisible(unitId, me.slug()) || !curriculum.hasLab(unitId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static MapSqlParameterSource params(String unitId, LearnerPrincipal me) {
    return new MapSqlParameterSource().addValue("learner", me.id()).addValue("unit", unitId);
  }
}
