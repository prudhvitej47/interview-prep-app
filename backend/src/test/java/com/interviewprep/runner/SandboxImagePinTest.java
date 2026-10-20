package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** update.sh pre-pulls the image named in deploy/sandbox-image; the runner uses DEFAULT_IMAGE. One image. */
class SandboxImagePinTest {

  @Test
  void theUpdaterPullsTheImageTheRunnerUses() throws Exception {
    assertThat(Files.readString(Path.of("../deploy/sandbox-image")).strip()).isEqualTo(DockerSandbox.DEFAULT_IMAGE);
  }
}
