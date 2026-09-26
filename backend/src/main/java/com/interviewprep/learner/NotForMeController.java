package com.interviewprep.learner;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerProfile.NotForMe;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Not for me": a learner keeps a topic or a single unit out of their own plans, without touching the
 * shared curriculum or the other learner's plans. Weights move whole domains; this is the finer tool,
 * for one topic or one unit inside a domain the learner otherwise wants.
 */
@RestController
@RequestMapping("/api/me/not-for-me")
class NotForMeController {

  record Change(String scope, String id, Boolean excluded) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final LearnerProfile profile;
  private final CurriculumQueries curriculum;

  NotForMeController(NamedParameterJdbcTemplate jdbc, LearnerProfile profile, CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.profile = profile;
    this.curriculum = curriculum;
  }

  @GetMapping
  NotForMe read(@AuthenticationPrincipal LearnerPrincipal me) {
    return profile.notForMe(me.id());
  }

  /** Marks one topic or unit, or clears the mark. Takes effect from the next plan drawn. */
  @PutMapping
  NotForMe change(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody Change body) {
    if (body == null || body.scope() == null || body.id() == null || body.excluded() == null) {
      throw bad("say the scope (topic or unit), the id, and whether it is excluded");
    }
    boolean known = switch (body.scope()) {
      case "topic" -> curriculum.topicPlaces().containsKey(body.id());
      case "unit" -> curriculum.unit(body.id(), me.slug()).isPresent();
      default -> throw bad("scope is topic or unit, not " + body.scope());
    };
    if (!known) {
      throw bad("unknown " + body.scope() + " " + body.id());
    }
    MapSqlParameterSource p = new MapSqlParameterSource()
        .addValue("learner", me.id()).addValue("scope", body.scope()).addValue("id", body.id());
    if (body.excluded()) {
      jdbc.update("insert into learner_exclusion (learner_id, scope, scope_id) values (:learner, :scope, :id)"
          + " on conflict do nothing", p);
    } else {
      jdbc.update("delete from learner_exclusion where learner_id = :learner and scope = :scope"
          + " and scope_id = :id", p);
    }
    return profile.notForMe(me.id());
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
