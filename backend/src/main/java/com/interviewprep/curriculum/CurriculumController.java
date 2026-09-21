package com.interviewprep.curriculum;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The curriculum as a learner browses it.
 *
 * <p>Takes the plain {@link Principal} rather than the learner module's type: the learner module
 * already depends on this one for the domain list, and depending back would be a cycle. The
 * principal's name is the learner's slug, which is all visibility needs.
 */
@RestController
class CurriculumController {

  private final CurriculumQueries queries;

  CurriculumController(CurriculumQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/api/topics")
  List<TopicSummary> topics(Principal learner) {
    return queries.topics(learner.getName());
  }

  @GetMapping("/api/topics/{id}")
  CurriculumQueries.TopicDetail topic(@PathVariable String id, Principal learner) {
    return queries.topic(id, learner.getName()).orElseThrow(CurriculumController::notFound);
  }

  // A private unit that belongs to someone else is a 404, not a 403: the other learner should not
  // be able to tell that it exists.
  @GetMapping("/api/units/{id}")
  CurriculumQueries.UnitDetail unit(@PathVariable String id, Principal learner) {
    return queries.unit(id, learner.getName()).orElseThrow(CurriculumController::notFound);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
