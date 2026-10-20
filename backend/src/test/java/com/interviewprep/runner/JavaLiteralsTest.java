package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JavaLiterals against check_coding.py's literal(), type by type. */
class JavaLiteralsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static String lit(String json, String type) {
    JsonNode value = JSON.readTree(json);
    return JavaLiterals.literal(value, type);
  }

  @Test
  void scalars() {
    assertThat(lit("7", "int")).isEqualTo("7");
    assertThat(lit("-3", "int")).isEqualTo("-3");
    assertThat(lit("9000000000", "long")).isEqualTo("9000000000L");
    assertThat(lit("2.5", "double")).isEqualTo("2.5");
    assertThat(lit("1e-7", "double")).isEqualTo("1.0E-7");
    assertThat(lit("true", "boolean")).isEqualTo("true");
    assertThat(lit("null", "String")).isEqualTo("null");
  }

  @Test
  void stringsAndCharsEscapeWhatJavaNeeds() {
    assertThat(lit("\"a\\\"b\\\\c\\nd\"", "String")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
    assertThat(lit("\"café\"", "String")).isEqualTo("\"café\"");
    assertThat(lit("\"x\"", "char")).isEqualTo("'x'");
    assertThat(lit("\"'\"", "char")).isEqualTo("'\\''");
    assertThat(lit("\"\\\\\"", "char")).isEqualTo("'\\\\'");
    assertThat(lit("\"\\n\"", "char")).isEqualTo("'\\n'");
    assertThatThrownBy(() -> lit("\"ab\"", "char")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void arraysListsAndTrees() {
    assertThat(lit("[2, 1, 5]", "int[]")).isEqualTo("new int[]{2, 1, 5}");
    assertThat(lit("[[1, 2], []]", "int[][]")).isEqualTo("new int[][]{new int[]{1, 2}, new int[]{}}");
    assertThat(lit("\"ab\"", "char[]")).isEqualTo("new char[]{'a', 'b'}");
    assertThat(lit("[\"ab\", [\"c\"]]", "char[][]")).isEqualTo("new char[][]{new char[]{'a', 'b'}, new char[]{'c'}}");
    assertThat(lit("[\"x\", \"y\"]", "String[]")).isEqualTo("new String[]{\"x\", \"y\"}");
    assertThat(lit("[]", "List<Integer>")).isEqualTo("new ArrayList<>()");
    assertThat(lit("[[1], [2, 3]]", "List<List<Integer>>"))
        .isEqualTo("new ArrayList<>(List.of(new ArrayList<>(List.of(1)), new ArrayList<>(List.of(2, 3))))");
    assertThat(lit("[3, 9, 20, null, null, 15, 7]", "TreeNode"))
        .isEqualTo("Harness.tree(3, 9, 20, null, null, 15, 7)");
    assertThat(lit("[]", "TreeNode")).isEqualTo("null");
  }

  @Test
  void everySchemaTypeIsKnownAndOthersAreRefused() {
    assertThat(JavaLiterals.TYPES).hasSize(21).contains("List<List<String>>", "String[][]", "TreeNode");
    assertThatThrownBy(() -> lit("1", "short")).isInstanceOf(IllegalArgumentException.class);
  }
}
