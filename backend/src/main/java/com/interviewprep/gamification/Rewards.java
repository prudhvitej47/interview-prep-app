package com.interviewprep.gamification;

import static com.interviewprep.planner.PlanQueries.mondayOf;

import com.interviewprep.planner.PlanQueries.WeekResult;
import com.interviewprep.progress.ProgressQueries.Attempt;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stars, streak and freezes for one learner, as a pure function of what they did (proposal G).
 *
 * <p><b>Stars</b> are never deducted for doing badly. A unit earns its stars the first time it is
 * finished with any rating but "again" (which means it was not managed): 3 for an LLD or HLD case,
 * 2 for a project deep dive, 1 for anything else, and a coding or SQL problem 1 more if it was
 * easy — the closest thing to "unaided and in time" a self-rating gives. A day with three or more
 * reviews earns 1. A week that meets its goal earns 5, and 2 more if the whole plan was done.
 *
 * <p><b>The streak</b> counts consecutive weeks that met their goal, from the first planned week.
 * A planned break neither counts nor breaks it. A missed week uses a freeze if one is banked, and
 * otherwise resets the streak. Freezes: 1 to start, 1 more for every 4 goal weeks in a row, at
 * most 2 banked. The week in progress only ever adds: once its goal is met it counts, and until
 * then it cannot cost anything.
 *
 * <p>Not yet: the mistake re-try star (there is no mistake log yet), pausing a prep cycle (the
 * streak should freeze, not break), and per-domain "interview-ready" marks (with the dashboard).
 */
final class Rewards {

  static final int START_FREEZES = 1;
  static final int MAX_FREEZES = 2;
  static final int GOAL_WEEKS_PER_FREEZE = 4;
  static final int REVIEWS_FOR_A_STAR = 3;
  static final List<Integer> MILESTONES = List.of(50, 100, 250);

  enum Outcome { GOAL_MET, BREAK, FREEZE_USED, MISSED, IN_PROGRESS }

  record Week(LocalDate weekStart, Outcome outcome) {}

  record Summary(int stars, int starsThisWeek, int streak, int longestStreak, int freezes,
      boolean thisWeekCounts, List<Week> recentWeeks, List<Integer> milestonesReached,
      Integer nextMilestone) {}

  private Rewards() {}

  /** Attempts oldest first; {@code types} maps each attempted unit to its type. */
  static Summary of(List<Attempt> attempts, Map<String, String> types, List<WeekResult> weeks,
      Set<LocalDate> breaks, LocalDate thisMonday) {
    int stars = 0;
    int starsThisWeek = 0;

    // Units: stars the first time each is finished with anything but "again".
    Set<String> started = new HashSet<>();
    Set<String> finished = new HashSet<>();
    Map<LocalDate, Integer> reviewsByDay = new HashMap<>();
    for (Attempt a : attempts) {
      if (!started.add(a.unitId())) {
        reviewsByDay.merge(a.day(), 1, Integer::sum);
      }
      if (!a.rating().equals("again") && finished.add(a.unitId())) {
        int s = unitStars(types.getOrDefault(a.unitId(), "concept"), a.rating());
        stars += s;
        if (!mondayOf(a.day()).isBefore(thisMonday)) {
          starsThisWeek += s;
        }
      }
    }
    for (var day : reviewsByDay.entrySet()) {
      if (day.getValue() >= REVIEWS_FOR_A_STAR) {
        stars++;
        if (!mondayOf(day.getKey()).isBefore(thisMonday)) {
          starsThisWeek++;
        }
      }
    }

    // Weeks, from the first plan up to last week; this week is looked at separately.
    Map<LocalDate, WeekResult> byWeek = new HashMap<>();
    weeks.forEach(w -> byWeek.put(w.weekStart(), w));
    for (WeekResult w : weeks) {
      if (w.goalMet()) {
        int s = 5 + (w.allDone() ? 2 : 0);
        stars += s;
        if (w.weekStart().equals(thisMonday)) {
          starsThisWeek += s;
        }
      }
    }
    int streak = 0;
    int longest = 0;
    int run = 0;
    int freezes = START_FREEZES;
    List<Week> history = new ArrayList<>();
    LocalDate first = weeks.isEmpty() ? thisMonday : weeks.getFirst().weekStart();
    for (LocalDate w = first; w.isBefore(thisMonday); w = w.plusWeeks(1)) {
      Outcome outcome;
      if (breaks.contains(w)) {
        outcome = Outcome.BREAK;
      } else if (byWeek.containsKey(w) && byWeek.get(w).goalMet()) {
        outcome = Outcome.GOAL_MET;
        streak++;
        run++;
        if (run % GOAL_WEEKS_PER_FREEZE == 0) {
          freezes = Math.min(MAX_FREEZES, freezes + 1);
        }
      } else if (freezes > 0) {
        outcome = Outcome.FREEZE_USED;
        freezes--;
        run = 0;
      } else {
        outcome = Outcome.MISSED;
        streak = 0;
        run = 0;
      }
      longest = Math.max(longest, streak);
      history.add(new Week(w, outcome));
    }
    boolean thisWeekCounts = byWeek.containsKey(thisMonday) && byWeek.get(thisMonday).goalMet();
    Outcome now = breaks.contains(thisMonday) ? Outcome.BREAK
        : thisWeekCounts ? Outcome.GOAL_MET : Outcome.IN_PROGRESS;
    history.add(new Week(thisMonday, now));
    int shownStreak = streak + (thisWeekCounts ? 1 : 0);
    longest = Math.max(longest, shownStreak);

    int total = stars;
    List<Integer> reached = MILESTONES.stream().filter(m -> total >= m).toList();
    Integer next = MILESTONES.stream().filter(m -> total < m).findFirst().orElse(null);
    List<Week> recent = history.subList(Math.max(0, history.size() - 8), history.size());
    return new Summary(stars, starsThisWeek, shownStreak, longest, freezes, thisWeekCounts,
        List.copyOf(recent), reached, next);
  }

  static int unitStars(String type, String rating) {
    return switch (type) {
      case "lld", "hld" -> 3;
      case "project" -> 2;
      case "coding", "sql" -> rating.equals("easy") ? 2 : 1;
      default -> 1;
    };
  }
}
