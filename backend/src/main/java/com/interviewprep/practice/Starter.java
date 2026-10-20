package com.interviewprep.practice;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The code a learner starts from the first time (runner design §2): the problem's signature as a stub
 * that compiles, returning a default, so the first Run already shows the cases failing rather than a
 * compiler error. TreeNode comes from the harness; List needs java.util.
 */
final class Starter {

  private Starter() {}

  static String of(JsonNode signature) {
    String returns = signature.path("returns").asString();
    List<String> params = new ArrayList<>();
    StringBuilder types = new StringBuilder(returns);
    for (JsonNode p : signature.path("params")) {
      params.add(p.path("type").asString() + " " + p.path("name").asString());
      types.append(' ').append(p.path("type").asString());
    }
    String imports = types.toString().contains("List<") ? "import java.util.*;\n\n" : "";
    return imports
        + "class Solution {\n"
        + "    public " + returns + " " + signature.path("method").asString() + "(" + String.join(", ", params) + ") {\n"
        + "        return " + defaultFor(returns) + ";\n"
        + "    }\n"
        + "}\n";
  }

  private static String defaultFor(String type) {
    return switch (type) {
      case "int", "long", "double", "char" -> "0";
      case "boolean" -> "false";
      default -> "null";
    };
  }
}
