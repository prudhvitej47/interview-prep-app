package com.interviewprep.curriculum;

import java.util.Collection;
import java.util.Set;

/**
 * Which units a learner has done, for the ticks beside the units a unit's text links to.
 *
 * <p>Declared here and implemented by the progress module, which owns attempts: progress already
 * depends on the curriculum, so the curriculum asking progress directly would be a cycle, and
 * reading the attempt table from here would break "owns no learner data".
 */
public interface UnitsDone {

  /** The units among {@code unitIds} that the learner with this slug has at least one attempt for. */
  Set<String> doneAmong(Collection<String> unitIds, String slug);
}
