package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tar the sandbox unpacks: read back with the system's own tar. */
class TarTest {

  private static String extract(byte[] tar, String name) throws Exception {
    Process p = new ProcessBuilder("tar", "-xOf", "-", name).start();
    p.getOutputStream().write(tar);
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor()).isZero();
    return out;
  }

  @Test
  void filesComeBackExactlyIncludingUnicodeAndSizesOnBlockEdges() throws Exception {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("Solution.java", "class Solution { String s = \"café 😀\"; }\n");
    files.put("Main.java", "x".repeat(512));
    files.put("TreeNode.java", "");
    byte[] tar = Tar.of(files);
    assertThat(tar.length % 512).isZero();
    for (Map.Entry<String, String> f : files.entrySet()) {
      assertThat(extract(tar, f.getKey())).isEqualTo(f.getValue());
    }
  }
}
