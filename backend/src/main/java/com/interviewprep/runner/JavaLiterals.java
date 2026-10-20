package com.interviewprep.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a typed JSON value into the Java expression that builds it. A port of the content repository's
 * scripts/check_coding.py literal(): CI checks the reference solutions with that one, the runner checks
 * learners' code with this one, so they must agree (HarnessLocalTest's drift table pins every type).
 */
final class JavaLiterals {

  private JavaLiterals() {}

  /** The types schemas/coding-tests.schema.json allows. */
  static final Set<String> TYPES = Set.of("int", "long", "double", "boolean", "char", "String",
      "int[]", "long[]", "double[]", "boolean[]", "char[]", "String[]", "int[][]", "char[][]", "String[][]",
      "List<Integer>", "List<Long>", "List<String>", "List<List<Integer>>", "List<List<String>>", "TreeNode");

  private static final Map<String, String> ELEMENT = Map.of("int[]", "int", "long[]", "long",
      "double[]", "double", "boolean[]", "boolean", "char[]", "char", "String[]", "String",
      "int[][]", "int[]", "char[][]", "char[]", "String[][]", "String[]");

  private static final Map<String, String> BOXED = Map.of("List<Integer>", "int", "List<Long>", "long",
      "List<String>", "String", "List<List<Integer>>", "List<Integer>", "List<List<String>>", "List<String>");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static String literal(JsonNode value, String type) {
    if (value == null || value.isNull()) {
      return "null";
    }
    switch (type) {
      case "int" -> { return Long.toString(value.asLong()); }
      case "long" -> { return value.asLong() + "L"; }
      case "double" -> { return doubleLiteral(value.asDouble()); }
      case "boolean" -> { return value.asBoolean() ? "true" : "false"; }
      case "char" -> { return charLiteral(value.asString()); }
      case "String" -> { return JSON.writeValueAsString(value.asString()); } // JSON escapes are valid Java escapes
      default -> { }
    }
    if (ELEMENT.containsKey(type)) {
      String inner = ELEMENT.get(type);
      List<String> parts = new ArrayList<>();
      if (inner.equals("char") && value.isString()) {
        // "abc" is accepted for char[], as in check_coding.py.
        value.asString().codePoints().forEach(cp -> parts.add(charLiteral(new String(Character.toChars(cp)))));
      } else {
        for (JsonNode v : value) {
          parts.add(literal(v, inner));
        }
      }
      return "new " + type + "{" + String.join(", ", parts) + "}";
    }
    if (BOXED.containsKey(type)) {
      if (value.isEmpty()) {
        return "new ArrayList<>()";
      }
      List<String> parts = new ArrayList<>();
      for (JsonNode v : value) {
        parts.add(literal(v, BOXED.get(type)));
      }
      return "new ArrayList<>(List.of(" + String.join(", ", parts) + "))";
    }
    if (type.equals("TreeNode")) {
      if (value.isEmpty()) {
        return "null";
      }
      List<String> parts = new ArrayList<>();
      for (JsonNode v : value) {
        parts.add(v.isNull() ? "null" : Long.toString(v.asLong()));
      }
      return "Harness.tree(" + String.join(", ", parts) + ")";
    }
    throw new IllegalArgumentException("unsupported type " + type);
  }

  private static String doubleLiteral(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("a double case value must be finite");
    }
    return Double.toString(d);
  }

  private static String charLiteral(String c) {
    if (c == null || c.codePointCount(0, c.length()) != 1 || c.length() != 1) {
      throw new IllegalArgumentException("char needs a one-character string, got " + c);
    }
    return switch (c.charAt(0)) {
      case '\'' -> "'\\''";
      case '\\' -> "'\\\\'";
      case '\n' -> "'\\n'";
      case '\r' -> "'\\r'";
      case '\t' -> "'\\t'";
      default -> c.charAt(0) < 0x20 ? "'\\u%04x'".formatted((int) c.charAt(0)) : "'" + c + "'";
    };
  }
}
