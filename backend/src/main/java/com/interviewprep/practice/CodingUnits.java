package com.interviewprep.practice;

import com.interviewprep.curriculum.CurriculumQueries;
import java.util.List;
import org.springframework.stereotype.Component;

/** Only a coding problem the learner can see has code to save or run; anything else is not found. */
@Component
class CodingUnits {

  private final CurriculumQueries curriculum;

  CodingUnits(CurriculumQueries curriculum) {
    this.curriculum = curriculum;
  }

  boolean visible(String unitId, String slug) {
    return curriculum.isVisible(unitId, slug) && "coding".equals(curriculum.typesOf(List.of(unitId)).get(unitId));
  }
}
