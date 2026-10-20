package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The sandbox command is the runner design's §4, flag for flag: a change here is a security review. */
class DockerSandboxArgsTest {

  @Test
  void theCommandIsExactlyTheDesignedOne() {
    List<String> args = new DockerSandbox("docker", "img@sha256:abc", null).runArgs("ipr-run-1");
    assertThat(args).containsExactly("run", "--rm", "-i", "--name", "ipr-run-1", "--label", "interview-prep.run=1",
        "--network", "none", "--read-only", "--user", "65534:65534",
        "--tmpfs", "/work:size=8m,mode=1777,noexec,nosuid", "--tmpfs", "/tmp:size=8m,noexec,nosuid",
        "--memory", "256m", "--memory-swap", "256m", "--cpus", "1", "--pids-limit", "64",
        "--ulimit", "nofile=64:64", "--ulimit", "nproc=64:64", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--ipc", "none", "--log-driver", "none",
        "img@sha256:abc", "sh", "-c",
        "tar -xf - -C /work && exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData"
            + " -Xmx160m -XX:ActiveProcessorCount=1 /work/Main.java");
  }

  @Test
  void aRuntimeGoesJustBeforeTheImage() {
    List<String> args = new DockerSandbox("docker", "img", "runsc").runArgs("n");
    int image = args.indexOf("img");
    assertThat(args.subList(image - 2, image)).containsExactly("--runtime", "runsc");
  }

  @Test
  void theDefaultImageIsTheTrialsPinnedDigest() {
    assertThat(DockerSandbox.DEFAULT_IMAGE).isEqualTo(
        "eclipse-temurin:25-jdk-noble@sha256:a75cd2f1bd3e1e45da898bf658f1f1039ab7392b34844862514d230fd6a516d4");
  }
}
