package com.interviewprep.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A topic or subtopic from the content repo's taxonomy, keyed by its permanent id.
 *
 * <p>The parent and domain links are plain ids rather than JPA associations. That is deliberate:
 * it keeps loading a topic from turning into a graph walk, and it is the same discipline that lets
 * other modules reference curriculum rows without reaching into this one's object graph.
 */
@Entity
@Table(name = "topic")
class Topic {

  @Id
  private String id;

  @Column(name = "domain_id", nullable = false)
  private String domainId;

  @Column(name = "parent_id")
  private String parentId;

  @Column(nullable = false)
  private String name;

  @Column(name = "sort_order", nullable = false)
  private short sortOrder;

  protected Topic() {
    // for JPA
  }

  String getId() {
    return id;
  }

  String getDomainId() {
    return domainId;
  }

  String getParentId() {
    return parentId;
  }

  String getName() {
    return name;
  }

  short getSortOrder() {
    return sortOrder;
  }
}
