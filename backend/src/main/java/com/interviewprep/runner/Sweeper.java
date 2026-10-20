package com.interviewprep.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Removes sandbox containers left behind by a crash (runner design §7): anything labelled
 * interview-prep.run=1 and older than a minute. A run lasts at most about 15 s, so nothing in progress
 * is ever that old, and nothing without the label (the app, Postgres) is ever looked at.
 */
final class Sweeper {

  static final long MAX_AGE_SECONDS = 60;

  private final String docker;
  private final Clock clock;

  Sweeper(String docker, Clock clock) {
    this.docker = docker;
    this.clock = clock;
  }

  int sweepOnce() {
    List<String> ids = lines(run(docker, "ps", "-aq", "--filter", "label=interview-prep.run=1"));
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> inspect = new ArrayList<>(List.of(docker, "inspect", "--format", "{{.Id}} {{.Created}}"));
    inspect.addAll(ids);
    int removed = 0;
    for (String line : lines(run(inspect.toArray(String[]::new)))) {
      String[] parts = line.split(" ", 2);
      if (parts.length != 2) {
        continue;
      }
      Instant created;
      try {
        created = Instant.parse(parts[1].strip());
      } catch (DateTimeParseException e) {
        continue;
      }
      if (Duration.between(created, clock.instant()).getSeconds() > MAX_AGE_SECONDS) {
        run(docker, "rm", "-f", parts[0]);
        removed++;
      }
    }
    return removed;
  }

  void start() {
    ScheduledExecutorService every = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "sandbox-sweeper");
      t.setDaemon(true);
      return t;
    });
    every.scheduleWithFixedDelay(() -> {
      try {
        int removed = sweepOnce();
        if (removed > 0) {
          System.getLogger(Sweeper.class.getName()).log(System.Logger.Level.INFO,
              "removed {0} stale sandbox container(s)", removed);
        }
      } catch (RuntimeException e) {
        System.getLogger(Sweeper.class.getName()).log(System.Logger.Level.WARNING,
            "sweep failed: {0}", e.getClass().getName());
      }
    }, 0, 60, TimeUnit.SECONDS);
  }

  private static String run(String... command) {
    try {
      Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor(15, TimeUnit.SECONDS);
      return out;
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static List<String> lines(String text) {
    return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }
}
