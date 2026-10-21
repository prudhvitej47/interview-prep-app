package com.interviewprep.practice;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The practice module's named refusals, as {"status": …} like the rest of its answers. */
@RestControllerAdvice(basePackageClasses = PracticeErrors.class)
class PracticeErrors {

  @ExceptionHandler(CodeDraftController.CodeDrafts.CodeTooBig.class)
  ResponseEntity<Map<String, String>> tooBig() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("status", "too-big"));
  }
}
