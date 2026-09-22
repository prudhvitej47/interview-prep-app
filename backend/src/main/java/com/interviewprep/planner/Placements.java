package com.interviewprep.planner;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the "Suggested placement" table a proposal's changelog ends with (see the content repo's
 * changes/README.md), so each new unit arrives pre-placed for each learner the way Claude proposed:
 *
 * <pre>
 * | Unit | Tripti | Prudhvi |
 * | --- | --- | --- |
 * | hld.modern.job-scheduler | now | next week |
 * </pre>
 *
 * A learner's column is found by their slug or display name. Anything unreadable falls back to
 * "next week", the proposal's default (F10), so a sloppy table can never hide a unit.
 */
final class Placements {

  static final String NOW = "now";
  static final String NEXT_WEEK = "next-week";
  static final String LATER = "end-of-track";

  private Placements() {}

  /** Unit id to choice for one learner; units missing from the table are simply absent. */
  static Map<String, String> suggested(String changelog, String slug, String displayName) {
    Map<String, String> out = new HashMap<>();
    if (changelog == null) {
      return out;
    }
    String[] sections = changelog.split("(?m)^## ");
    for (String section : sections) {
      if (!section.toLowerCase(Locale.ROOT).startsWith("suggested placement")) {
        continue;
      }
      List<String[]> rows = section.lines().map(String::trim).filter(l -> l.startsWith("|"))
          .map(l -> l.replaceAll("^\\||\\|$", "").split("\\|")).toList();
      if (rows.size() < 3) {
        return out;
      }
      int column = -1;
      String[] header = rows.getFirst();
      for (int i = 1; i < header.length; i++) {
        String h = header[i].trim().toLowerCase(Locale.ROOT);
        if (h.equals(slug.toLowerCase(Locale.ROOT)) || h.equals(displayName.toLowerCase(Locale.ROOT))) {
          column = i;
        }
      }
      if (column < 0) {
        return out;
      }
      for (String[] row : rows.subList(2, rows.size())) {
        if (row.length > column) {
          out.put(row[0].trim().replace("`", ""), choice(row[column]));
        }
      }
    }
    return out;
  }

  static String choice(String text) {
    String t = text.trim().toLowerCase(Locale.ROOT);
    if (t.startsWith("now")) {
      return NOW;
    }
    if (t.contains("end of track") || t.contains("later") || t.contains("end-of-track")) {
      return LATER;
    }
    return NEXT_WEEK;
  }
}
