package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sweeper against a fake docker: only labelled containers older than a minute go. */
class SweeperTest {

  @TempDir Path dir;

  /** A fake docker: `ps` and `inspect` print the given text (escapes like \\n are interpreted), `rm` records. */
  private Path fakeDocker(String ps, String inspect) throws Exception {
    Path removed = dir.resolve("removed");
    Path fake = dir.resolve("docker");
    String script = "#!/bin/sh\n"
        + "case \"$1\" in\n"
        + "  ps) [ \"$4\" = \"label=interview-prep.run=1\" ] && printf '" + ps + "' ;;\n"
        + "  inspect) printf '" + inspect + "' ;;\n"
        + "  rm) echo \"$3\" >> '" + removed + "' ;;\n"
        + "esac\n"
        + "exit 0\n";
    Files.writeString(fake, script);
    fake.toFile().setExecutable(true);
    return fake;
  }

  @Test
  void removesOnlyLabelledContainersOlderThanAMinute() throws Exception {
    Instant now = Instant.parse("2026-10-20T15:00:00Z");
    Path docker = fakeDocker("old1\\nnew1\\n",
        "old1 2026-10-20T14:58:30.123456789Z\\nnew1 2026-10-20T14:59:45Z\\n");
    int removed = new Sweeper(docker.toString(), Clock.fixed(now, ZoneOffset.UTC)).sweepOnce();
    assertThat(removed).isEqualTo(1);
    assertThat(Files.readAllLines(dir.resolve("removed"))).containsExactly("old1");
  }

  @Test
  void nothingLabelledMeansNothingRemoved() throws Exception {
    Path docker = fakeDocker("", "");
    assertThat(new Sweeper(docker.toString(), Clock.systemUTC()).sweepOnce()).isZero();
    assertThat(dir.resolve("removed")).doesNotExist();
  }

  @Test
  void aLineItCannotReadIsSkippedNotFatal() throws Exception {
    Path docker = fakeDocker("x\\n", "x not-a-date\\n");
    assertThat(new Sweeper(docker.toString(), Clock.systemUTC()).sweepOnce()).isZero();
  }

  private Path script(String name, String body) throws Exception {
    Path p = dir.resolve(name);
    Files.writeString(p, "#!/bin/sh\n" + body);
    p.toFile().setExecutable(true);
    return p;
  }

  @Test
  void aDockerThatHangsIsStoppedSoTheSweeperCarriesOn() throws Exception {
    Path hang = script("hang", "exec sleep 30\n");
    long start = System.nanoTime();
    assertThat(new Sweeper(hang.toString(), Clock.systemUTC(), Duration.ofMillis(500)).sweepOnce()).isZero();
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
  }

  @Test
  void aRemovalThatFailsIsNotCounted() throws Exception {
    Path docker = script("failing-rm", "case \"$1\" in\n"
        + "  ps) printf 'old1\\n' ;;\n"
        + "  inspect) printf 'old1 2026-10-20T14:50:00Z\\n' ;;\n"
        + "  rm) exit 1 ;;\n"
        + "esac\n");
    Instant now = Instant.parse("2026-10-20T15:00:00Z");
    assertThat(new Sweeper(docker.toString(), Clock.fixed(now, ZoneOffset.UTC)).sweepOnce()).isZero();
  }
}
