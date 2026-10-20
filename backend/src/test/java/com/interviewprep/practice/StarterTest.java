package com.interviewprep.practice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StarterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static String starter(String signature) {
    return Starter.of(JSON.readTree(signature));
  }

  @Test
  void aStubThatCompilesWithTheProblemsSignature() {
    assertThat(starter("{\"method\": \"maxSum\", \"params\": [{\"name\": \"nums\", \"type\": \"int[]\"},"
        + " {\"name\": \"k\", \"type\": \"int\"}], \"returns\": \"int\"}")).isEqualTo("""
        class Solution {
            public int maxSum(int[] nums, int k) {
                return 0;
            }
        }
        """);
  }

  @Test
  void listsImportJavaUtilAndEveryReturnTypeHasADefault() {
    assertThat(starter("{\"method\": \"validate\", \"params\": [{\"name\": \"lines\", \"type\": \"String[]\"}],"
        + " \"returns\": \"List<String>\"}")).isEqualTo("""
        import java.util.*;

        class Solution {
            public List<String> validate(String[] lines) {
                return null;
            }
        }
        """);
    assertThat(starter("{\"method\": \"ok\", \"params\": [], \"returns\": \"boolean\"}")).contains("return false;");
    assertThat(starter("{\"method\": \"avg\", \"params\": [], \"returns\": \"double\"}")).contains("return 0;");
    assertThat(starter("{\"method\": \"first\", \"params\": [], \"returns\": \"char\"}")).contains("return 0;");
    assertThat(starter("{\"method\": \"root\", \"params\": [{\"name\": \"root\", \"type\": \"TreeNode\"}],"
        + " \"returns\": \"TreeNode\"}")).contains("public TreeNode root(TreeNode root)").contains("return null;")
        .doesNotContain("import");
  }
}
