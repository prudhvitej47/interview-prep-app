package com.interviewprep.projects;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The limits on a project and its questions, the same whether they arrive in a file or are typed in
 * the app: generous for what each field holds, and a stop for input that is not what it seems.
 */
final class ProjectFields {

  static final int MAX_PROJECTS = 30;
  static final int MAX_QUESTIONS = 30;
  static final Set<String> RUNGS = Set.of("walkthrough", "why", "scale", "failure", "change", "story");

  static final int MAX_NAME = 200;
  static final int MAX_SUMMARY = 20_000;
  static final int MAX_PROMPT = 2_000;
  static final int MAX_PROBES = 10_000;
  static final int MAX_STRONG_ANSWER = 20_000;
  static final int MAX_LINKS = 20;
  static final int MAX_ID = 200;
  static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

  private ProjectFields() {}

  /** The value stripped, or "" when absent; a problem is added when it is required and blank, or too long. */
  static String text(String value, String label, int max, boolean required, List<String> problems) {
    String s = value == null ? "" : value.strip();
    if (required && s.isEmpty()) {
      problems.add(label + " is empty.");
    } else if (s.length() > max) {
      problems.add(label + " is longer than " + max + " characters.");
    }
    return s;
  }

  /** Unit ids, blanks and repeats left out, in the order given. */
  static List<String> ids(List<String> values, String label, List<String> problems) {
    List<String> out = new ArrayList<>();
    if (values == null) {
      return out;
    }
    for (String v : values) {
      String id = v == null ? "" : v.strip();
      if (id.isEmpty() || out.contains(id)) {
        continue;
      }
      if (id.length() > MAX_ID) {
        problems.add(label + " holds an id longer than " + MAX_ID + " characters.");
        return List.of();
      }
      out.add(id);
    }
    if (out.size() > MAX_LINKS) {
      problems.add(label + " may hold at most " + MAX_LINKS + " units.");
      return List.of();
    }
    return out;
  }
}
