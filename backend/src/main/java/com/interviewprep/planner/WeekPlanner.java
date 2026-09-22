package com.interviewprep.planner;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Builds one learner's week (proposal section F). A pure function of its input, so every rule is
 * testable without a database:
 *
 * <ol>
 *   <li><b>Budget</b>: 90% of the stated hours; up to a fifth of that for reviews that fall due; the
 *       goal is 80% of the plan, so one bad day still makes the week (F1).
 *   <li><b>Shares</b>: each domain's weight × a weakness factor from 1.5 (rated 0) down to 0.7 (rated
 *       5), capped at half to double the weight, normalised over domains that have units (F2).
 *   <li><b>Ranking</b> inside a domain: relevance × gap × fit. Relevance grows with interview
 *       reports on the topic; gap is 1 − strength/5; a unit more than one step above the learner's
 *       level counts half. A unit is ready once its prerequisites are done or already in this week
 *       (F3).
 *   <li><b>Mix</b>: one item from each required group first, then minutes go to whichever domain is
 *       furthest behind its share (weighted fair queuing); at most two heavy units (75+ minutes)
 *       and never two on one day; DSA spread over separate days where possible (F4).
 *   <li><b>Placement</b>: least-loaded day, never before a prerequisite, heavy units towards the
 *       weekend.
 * </ol>
 *
 * <p>Units a learner placed "now" are taken first; units placed "later" never reach this function.
 *
 * <p>Not yet, and why: the company factor (no target companies are captured yet), the catch-up
 * factor (needs weeks of plans first), progressive difficulty from solve history (F5), interview
 * mode (F8), and swap/skip/pin (F9). Each arrives when the data it needs exists.
 */
final class WeekPlanner {

  static final int VERSION = 1;
  static final double PLANNED_SHARE_OF_HOURS = 0.9;
  static final double REVIEW_SHARE = 0.2;
  static final double GOAL_SHARE = 0.8;
  static final int HEAVY_MINUTES = 75;
  static final int MAX_HEAVY = 2;

  record Candidate(String unitId, String title, String type, String topicId, String topicName,
      String domainId, int difficulty, int minutes, List<String> prerequisites, double strength,
      int reports) {
    boolean heavy() {
      return minutes >= HEAVY_MINUTES;
    }
  }

  record DueReview(String unitId, String type, int unitMinutes, LocalDate dueOn) {}

  record Domain(String id, String name, int weight, double strength) {}

  /**
   * {@code fromDay} is the first day still ahead (1 = Monday): a week first opened on a Wednesday is
   * planned over the study days left, with its minutes cut to match, not crammed into them.
   */
  record Input(LocalDate weekStart, int fromDay, List<Integer> studyDays, double hoursPerWeek,
      double loadFactor, List<Domain> domains, List<Candidate> candidates, Set<String> done,
      List<DueReview> reviews, Set<String> startNow) {}

  record Item(String unitId, int day, String kind, int minutes, String reason) {}

  record Share(String domainId, String name, int percent) {}

  record Plan(int plannedMinutes, int goalMinutes, List<Item> items, List<Share> shares,
      List<String> notes) {}

  // The label reads in both "Required every week: X" and "No X units to learn yet".
  private record Group(String label, Predicate<Candidate> member) {}

  // F4: every week has one of each, when the curriculum has one to give.
  private static final List<Group> REQUIRED = List.of(
      new Group("DSA", c -> c.domainId().equals("dsa")),
      new Group("Java or Spring", c -> Set.of("java", "spring").contains(c.domainId())),
      new Group("databases or distributed systems",
          c -> Set.of("databases", "data", "distributed").contains(c.domainId())),
      new Group("low-level design", c -> c.domainId().equals("lld")),
      new Group("system design", c -> c.domainId().equals("hld")),
      new Group("scenario, project or behavioural",
          c -> Set.of("scenario", "project", "behavioral").contains(c.type())));

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);

  private WeekPlanner() {}

  static Plan plan(Input in) {
    List<Integer> days = in.studyDays().stream().filter(d -> d >= in.fromDay()).sorted().toList();
    List<String> notes = new ArrayList<>();
    if (days.isEmpty()) {
      notes.add("No study days are left this week; the next plan starts on Monday.");
      return new Plan(0, 0, List.of(), List.of(), notes);
    }
    double weekLeft = (double) days.size() / in.studyDays().size();
    int planned = (int) Math.round(
        in.hoursPerWeek() * 60 * PLANNED_SHARE_OF_HOURS * in.loadFactor() * weekLeft);
    int goal = (int) Math.round(planned * GOAL_SHARE);
    if (days.size() < in.studyDays().size()) {
      notes.add("Made mid-week, so it covers the " + days.size() + " study days left.");
    }

    // Reviews first: what fell due earliest, up to a fifth of the week.
    List<DueReview> reviews = new ArrayList<>(in.reviews());
    reviews.sort(Comparator.comparing(DueReview::dueOn).thenComparing(DueReview::unitId));
    List<DueReview> takenReviews = new ArrayList<>();
    int reviewMinutes = 0;
    for (DueReview r : reviews) {
      int m = reviewMinutes(r);
      if (reviewMinutes + m <= planned * REVIEW_SHARE) {
        takenReviews.add(r);
        reviewMinutes += m;
      }
    }
    int learnBudget = planned - reviewMinutes;
    if (takenReviews.size() < reviews.size()) {
      notes.add((reviews.size() - takenReviews.size()) + " due reviews did not fit; they stay in the"
          + " review list on the home page.");
    }

    // Shares, over the domains that have something to learn and a weight above zero.
    Set<String> withUnits = new HashSet<>();
    in.candidates().forEach(c -> withUnits.add(c.domainId()));
    Map<String, Double> raw = new LinkedHashMap<>();
    for (Domain d : in.domains()) {
      if (d.weight() > 0 && withUnits.contains(d.id())) {
        double factor = 1.5 - 0.16 * Math.clamp(d.strength(), 0, 5);
        raw.put(d.id(), Math.clamp(d.weight() * factor, d.weight() * 0.5, d.weight() * 2.0));
      }
    }
    double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
    Map<String, Double> share = new LinkedHashMap<>();
    raw.forEach((id, v) -> share.put(id, v / total));
    List<Share> shares = in.domains().stream().filter(d -> share.containsKey(d.id()))
        .map(d -> new Share(d.id(), d.name(), (int) Math.round(share.get(d.id()) * 100))).toList();
    Map<String, String> domainNames = new HashMap<>();
    in.domains().forEach(d -> domainNames.put(d.id(), d.name()));

    // Learning: required groups first, then the domain furthest behind its share.
    List<Candidate> ranked = in.candidates().stream()
        .filter(c -> share.containsKey(c.domainId()))
        .sorted(Comparator.comparingDouble(WeekPlanner::score).reversed()
            .thenComparingInt(Candidate::difficulty).thenComparing(Candidate::unitId))
        .toList();
    Set<String> known = new HashSet<>(in.done());
    in.candidates().forEach(c -> known.add(c.unitId()));
    Picker picker = new Picker(learnBudget, in.done(), known);

    // New units the learner chose to start now come before everything else (F10).
    for (Candidate c : ranked) {
      if (in.startNow().contains(c.unitId()) && picker.canTake(c)) {
        picker.take(c, DashboardController.NOW_REASON);
      }
    }
    for (Group g : REQUIRED) {
      List<Candidate> members = ranked.stream().filter(g.member()).toList();
      if (members.isEmpty()) {
        if (in.candidates().stream().noneMatch(g.member())) {
          notes.add("No " + g.label() + " units to learn yet, so this week has none.");
        }
        continue;
      }
      members.stream().filter(picker::canTake).findFirst().ifPresentOrElse(
          c -> picker.take(c, "Required every week: " + g.label()),
          () -> notes.add("No " + g.label() + " unit fitted this week."));
    }
    Map<String, Double> target = new HashMap<>();
    share.forEach((id, s) -> target.put(id, s * learnBudget));
    while (true) {
      Candidate next = null;
      double bestDeficit = Double.NEGATIVE_INFINITY;
      for (String domain : share.keySet()) {
        double deficit = target.get(domain) - picker.assigned.getOrDefault(domain, 0);
        if (deficit <= bestDeficit) {
          continue;
        }
        for (Candidate c : ranked) {
          if (c.domainId().equals(domain) && picker.canTake(c)) {
            next = c;
            bestDeficit = deficit;
            break;
          }
        }
      }
      if (next == null) {
        break;
      }
      picker.take(next, null);
    }
    if (picker.remaining > 30) {
      notes.add(picker.remaining + " minutes are unplanned: nothing else to learn is ready yet.");
    }

    // Placement.
    Map<Integer, Integer> load = new HashMap<>();
    Map<Integer, Integer> dsaOn = new HashMap<>();
    Set<Integer> heavyDays = new HashSet<>();
    Map<String, Integer> dayOf = new HashMap<>();
    double perDay = (double) planned / days.size();
    List<Item> items = new ArrayList<>();
    for (Picked p : picker.picked) {
      Candidate c = p.candidate();
      int earliest = c.prerequisites().stream().filter(dayOf::containsKey).mapToInt(dayOf::get)
          .max().orElse(days.getFirst());
      List<Integer> allowed = days.stream().filter(d -> d >= earliest).toList();
      if (c.heavy() && allowed.stream().anyMatch(d -> !heavyDays.contains(d))) {
        allowed = allowed.stream().filter(d -> !heavyDays.contains(d)).toList();
      }
      int day = allowed.stream().min(Comparator.comparingDouble((Integer d) -> {
        double cost = load.getOrDefault(d, 0);
        if (c.domainId().equals("dsa")) {
          cost += dsaOn.getOrDefault(d, 0) * perDay;  // spread DSA across days
        }
        if (c.heavy() && d >= 6) {
          cost -= perDay;  // long sessions suit the weekend
        }
        return cost;
      }).thenComparingInt(d -> d)).orElseThrow();
      dayOf.put(c.unitId(), day);
      load.merge(day, c.minutes(), Integer::sum);
      if (c.domainId().equals("dsa")) {
        dsaOn.merge(day, 1, Integer::sum);
      }
      if (c.heavy()) {
        heavyDays.add(day);
      }
      items.add(new Item(c.unitId(), day, "learn", c.minutes(),
          reason(c, p.why(), domainNames.get(c.domainId()), share.get(c.domainId()))));
    }
    for (DueReview r : takenReviews) {
      int dueDay = r.dueOn().isBefore(in.weekStart()) ? 1 : r.dueOn().getDayOfWeek().getValue();
      List<Integer> allowed = days.stream().filter(d -> d >= dueDay).toList();
      int day = (allowed.isEmpty() ? days : allowed).stream()
          .min(Comparator.comparingInt((Integer d) -> load.getOrDefault(d, 0)).thenComparingInt(d -> d))
          .orElseThrow();
      load.merge(day, reviewMinutes(r), Integer::sum);
      String when = r.dueOn().isBefore(in.weekStart()) ? "overdue since " + DAY.format(r.dueOn())
          : "due " + DAY.format(r.dueOn());
      items.add(new Item(r.unitId(), day, "review", reviewMinutes(r),
          "Review " + when + ". " + HOW_TO_REVIEW.getOrDefault(r.type(), "Recall it before reopening it.")));
    }
    items.sort(Comparator.comparingInt(Item::day).thenComparing(i -> i.kind().equals("review") ? 1 : 0));
    return new Plan(planned, goal, items, shares, notes);
  }

  private static final Map<String, String> HOW_TO_REVIEW = Map.of(
      "coding", "Solve it again without notes.",
      "sql", "Write the query again from scratch.",
      "lld", "Outline the design from memory in 15 minutes.",
      "hld", "Outline the design from memory in 15 minutes.");

  /** A review is shorter than learning: an outline for design, about half the time otherwise. */
  static int reviewMinutes(DueReview r) {
    if (r.type().equals("lld") || r.type().equals("hld")) {
      return 15;
    }
    return Math.clamp((int) Math.ceil(r.unitMinutes() / 2.0), 10, 30);
  }

  static double score(Candidate c) {
    double relevance = 1 + 0.25 * Math.min(c.reports(), 4);
    double gap = Math.max(0.1, 1 - c.strength() / 5);
    double fit = c.difficulty() <= Math.floor(c.strength()) + 1 ? 1 : 0.5;
    return relevance * gap * fit;
  }

  private static String reason(Candidate c, String why, String domainName, double share) {
    List<String> parts = new ArrayList<>();
    if (why != null) {
      parts.add(why);
    }
    parts.add(domainName + " is " + Math.round(share * 100) + "% of this week");
    parts.add("you are at %.1f/5 in %s".formatted(c.strength(), c.topicName()));
    if (c.reports() > 0) {
      parts.add("asked about in " + c.reports() + (c.reports() == 1 ? " interview report" : " interview reports"));
    }
    String text = String.join("; ", parts);
    return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
  }

  private record Picked(Candidate candidate, String why) {}

  /** The running state of the week while units are chosen. */
  private static final class Picker {
    int remaining;
    final Set<String> done;
    final Set<String> known;
    final Set<String> taken = new HashSet<>();
    final List<Picked> picked = new ArrayList<>();
    final Map<String, Integer> assigned = new HashMap<>();
    int heavy;

    Picker(int budget, Set<String> done, Set<String> known) {
      this.remaining = budget;
      this.done = done;
      this.known = known;
    }

    boolean canTake(Candidate c) {
      return !taken.contains(c.unitId())
          && c.minutes() <= remaining
          && (!c.heavy() || heavy < MAX_HEAVY)
          // Ready: every prerequisite that still exists is done or already earlier this week.
          && c.prerequisites().stream().allMatch(p -> !known.contains(p) || done.contains(p) || taken.contains(p));
    }

    void take(Candidate c, String why) {
      taken.add(c.unitId());
      picked.add(new Picked(c, why));
      assigned.merge(c.domainId(), c.minutes(), Integer::sum);
      remaining -= c.minutes();
      if (c.heavy()) {
        heavy++;
      }
    }
  }
}
