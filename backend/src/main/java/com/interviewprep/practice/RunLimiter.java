package com.interviewprep.practice;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** One run in flight per learner and at most ten a minute (runner design §6). */
@Component
public class RunLimiter {

  static final int PER_MINUTE = 10;

  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final Map<Long, Deque<Instant>> recent = new ConcurrentHashMap<>();

  /** Null when the run may go ahead; otherwise the status to answer with. */
  String acquire(long learnerId) {
    Instant now = Instant.now();
    Deque<Instant> times = recent.computeIfAbsent(learnerId, id -> new ArrayDeque<>());
    synchronized (times) {
      while (!times.isEmpty() && times.peekFirst().isBefore(now.minusSeconds(60))) {
        times.pollFirst();
      }
      if (times.size() >= PER_MINUTE) {
        return "too-many";
      }
      if (!inFlight.add(learnerId)) {
        return "one-at-a-time";
      }
      times.addLast(now);
      return null;
    }
  }

  void release(long learnerId) {
    inFlight.remove(learnerId);
  }

  public void reset() {
    inFlight.clear();
    recent.clear();
  }
}
