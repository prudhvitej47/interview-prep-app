package com.interviewprep.runner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what the generated Main.java printed: @@START, one @@CASE {json} per case, @@TIMEOUT "id",
 * @@OUTPUT "text", @@END. Anything else (compiler messages, JVM errors) is ignored here and judged by the
 * caller from what is missing. The first line for a case id wins.
 */
final class HarnessOutput {

  record CaseLine(String id, String verdict, String got, String error, long ms) {}

  record Parsed(boolean started, boolean ended, List<CaseLine> cases, String timedOut, String output) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HarnessOutput() {}

  static Parsed parse(String stdout) {
    boolean started = false;
    boolean ended = false;
    String timedOut = null;
    String output = "";
    List<CaseLine> cases = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String line : stdout.split("\n")) {
      String l = line.stripTrailing();
      try {
        if (l.equals("@@START")) {
          started = true;
        } else if (l.equals("@@END")) {
          ended = true;
        } else if (l.startsWith("@@TIMEOUT ")) {
          timedOut = JSON.readTree(l.substring("@@TIMEOUT ".length())).asString();
        } else if (l.startsWith("@@OUTPUT ")) {
          output = JSON.readTree(l.substring("@@OUTPUT ".length())).asString();
        } else if (l.startsWith("@@CASE ")) {
          JsonNode c = JSON.readTree(l.substring("@@CASE ".length()));
          String id = c.path("case").asString();
          if (seen.add(id)) {
            cases.add(new CaseLine(id, c.path("verdict").asString(), text(c.path("got")),
                text(c.path("error")), c.path("ms").asLong()));
          }
        }
      } catch (RuntimeException malformed) {
        // A line that only looks like a marker is ignored, like any other noise.
      }
    }
    return new Parsed(started, ended, List.copyOf(cases), timedOut, output);
  }

  private static String text(JsonNode node) {
    return node.isNull() || node.isMissingNode() ? null : node.asString();
  }
}
