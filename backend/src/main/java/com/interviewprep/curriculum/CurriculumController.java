package com.interviewprep.curriculum;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the loaded curriculum. Empty until the content loader runs (PR 6), which is exactly what
 * makes it a useful first endpoint: it proves the browser, Spring MVC, JPA, Flyway and Postgres
 * are wired together before there is any content to confuse the picture.
 */
@RestController
@RequestMapping("/api/topics")
class CurriculumController {

  private final TopicRepository topics;

  CurriculumController(TopicRepository topics) {
    this.topics = topics;
  }

  @GetMapping
  List<TopicSummary> listTopics() {
    return topics.findAllByOrderByDomainIdAscSortOrderAsc().stream()
        .map(t -> new TopicSummary(t.getId(), t.getDomainId(), t.getParentId(), t.getName()))
        .toList();
  }
}
