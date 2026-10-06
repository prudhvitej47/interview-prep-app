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
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Builds one learner's week (proposal section F). A pure function of its input, so every rule is
 * testable without a database:
 *
 * <ol>
 *   <li><b>Budget</b>: 90% of the stated hours; up to a tenth of that for reviews that fall due; the
 *       goal is 80% of the plan, so one bad day still makes the week (F1).
 *   <li><b>Shares</b>: each domain's weight × a weakness factor from 1.5 (rated 0) down to 0.7 (rated
 *       5), capped at half to double the weight, normalised over domains that have units (F2).
 *   <li><b>Ranking</b> inside a domain: relevance × gap × fit. Relevance grows with interview
 *       reports on the topic; gap is 1 − strength/5; a unit more than one step above the learner's
 *       level counts half. A unit is ready once its prerequisites are done or already in this week
 *       (F3).
 *   <li><b>Mix</b>: minutes go to whichever domain is furthest behind its share (weighted fair
 *       queuing), so a domain gets what its weight says: no domain is guaranteed a unit, and a short
 *       unit from a small domain gets in when its turn comes and it fits; at most two heavy units (75+
 *       minutes) and never two on one day; DSA spread over separate days where possible (F4).
 *   <li><b>Placement</b>: least-loaded day, never before a prerequisite, heavy units towards the
 *       weekend.
 * </ol>
 *
 * <p>Units a learner placed "now" are taken first; units placed "later" never reach this function.
 * Last week's unfinished learning gets no priority: those units rejoin the pool at their designed
 * minutes and compete by rank and share like any other.
 *
 * <p><b>Project questions</b> (the learner's own projects) come off the top like reviews, up to
 * {@link #PROJECT_SHARE} of the week, and never two on one day: last week's unrated ones first
 * (the caller stops carrying one after two carry-overs), then those due for another rehearsal, earliest
 * first, then new ones in ladder order, one project at a time (a project already started comes
 * before one not yet begun). Taking them off the top, rather than making projects a domain, keeps
 * each domain's share meaning what the learner set: a project question is payments, databases and
 * behavioural at once. A learner with no project questions gets exactly the plan they got before.
 *
 * <p>Not yet, and why: the company factor (no target companies are captured yet), the catch-up
 * factor (needs weeks of plans first), progressive difficulty from solve history (F5), interview
 * mode (F8), and swap/skip/pin (F9). Each arrives when the data it needs exists.
 */
final class WeekPlanner {

  static final int VERSION = 2;
  static final double PLANNED_SHARE_OF_HOURS = 0.9;
  static final double REVIEW_SHARE = 0.1;
  static final double GOAL_SHARE = 0.8;
  static final int HEAVY_MINUTES = 75;
  static final int MAX_HEAVY = 2;
  /** Marks a project question carried over from an earlier plan; the caller counts these. */
  static final String CARRIED = "Carried over from last week";
  /** Project questions take at most this share of the planned minutes, off the top like reviews. */
  static final double PROJECT_SHARE = 0.1;

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
   * A question about one of the learner's own projects. The orders are its project's place on the
   * My projects page and its own place on that project's ladder; {@code dueOn} is null when it has
   * never been rated.
   */
  record ProjectQuestion(long questionId, long projectId, String projectName, int projectOrder,
      int questionOrder, String rung, int minutes, LocalDate dueOn) {}

  /**
   * {@code fromDay} is the first day still ahead (1 = Monday): a week first opened on a Wednesday is
   * planned over the study days left, with its minutes cut to match, not crammed into them.
   */
  record Input(LocalDate weekStart, int fromDay, List<Integer> studyDays, double hoursPerWeek,
      double loadFactor, List<Domain> domains, List<Candidate> candidates, Set<String> done,
      List<DueReview> reviews, Set<String> startNow, List<ProjectQuestion> projectQuestions,
      List<Long> carriedQuestions) {

    /** A learner with no project questions. */
    Input(LocalDate weekStart, int fromDay, List<Integer> studyDays, double hoursPerWeek,
        double loadFactor, List<Domain> domains, List<Candidate> candidates, Set<String> done,
        List<DueReview> reviews, Set<String> startNow) {
      this(weekStart, fromDay, studyDays, hoursPerWeek, loadFactor, domains, candidates, done, reviews,
          startNow, List.of(), List.of());
    }
  }

  /** About a unit ({@code learn}, {@code review}) or, with kind {@code project}, a project question. */
  record Item(String unitId, int day, String kind, int minutes, String reason, Long projectQuestionId) {
    Item(String unitId, int day, String kind, int minutes, String reason) {
      this(unitId, day, kind, minutes, reason, null);
    }
  }

  record Share(String domainId, String name, int percent) {}

  record Plan(int plannedMinutes, int goalMinutes, List<Item> items, List<Share> shares,
      List<String> notes) {}

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
    if (takenReviews.size() < reviews.size()) {
      notes.add((reviews.size() - takenReviews.size()) + " due reviews did not fit; they stay in the"
          + " review list on the home page.");
    }

    // Project questions next, also off the top, within their own share.
    ProjectPick projects = pickProjects(in, planned, days.size(), notes);
    int learnBudget = planned - reviewMinutes - projects.minutes();

    // Shares, over the domains that have something to learn and a weight above zero.
    Set<String> withUnits = new HashSet<>();
    in.candidates().forEach(c -> withUnits.add(c.domainId()));
    Map<String, Double> share = shareOf(in.domains(), withUnits);
    List<Share> shares = in.domains().stream().filter(d -> share.containsKey(d.id()))
        .map(d -> new Share(d.id(), d.name(), percent(share.get(d.id())))).toList();
    Map<String, String> domainNames = new HashMap<>();
    in.domains().forEach(d -> domainNames.put(d.id(), d.name()));

    // Learning: units placed "now" first, then the domain furthest behind its share.
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
    // Project questions last, so "least busy" sees everything else; never two on one day, and a
    // due one not before its due day when a free day allows.
    Set<Integer> projectDays = new HashSet<>();
    for (TakenQuestion t : projects.taken()) {
      ProjectQuestion q = t.question();
      int dueDay = q.dueOn() == null || q.dueOn().isBefore(in.weekStart()) ? 1 : q.dueOn().getDayOfWeek().getValue();
      List<Integer> free = days.stream().filter(d -> !projectDays.contains(d)).toList();
      List<Integer> allowed = free.stream().filter(d -> d >= dueDay).toList();
      int day = (allowed.isEmpty() ? free : allowed).stream()
          .min(Comparator.comparingInt((Integer d) -> load.getOrDefault(d, 0)).thenComparingInt(d -> d))
          .orElseThrow();
      projectDays.add(day);
      load.merge(day, q.minutes(), Integer::sum);
      items.add(new Item(null, day, "project", q.minutes(), t.reason(), q.questionId()));
    }
    items.sort(Comparator.comparingInt(Item::day).thenComparingInt(i -> switch (i.kind()) {
      case "review" -> 1;
      case "project" -> 2;
      default -> 0;
    }));
    return new Plan(planned, goal, items, shares, notes);
  }

  private record TakenQuestion(ProjectQuestion question, String reason) {}

  private record ProjectPick(List<TakenQuestion> taken, int minutes) {}

  private static final Map<String, String> RUNG = Map.of(
      "walkthrough", "the walk-through", "why", "why it was built this way", "scale", "what happens at scale",
      "failure", "what happens when a part fails", "change", "what you would change", "story", "the story behind it");

  /**
   * The week's project questions (see the class comment). Each is taken only if it fits the
   * project share and a study day without one is left. New questions follow the ladder: once one is
   * taken, a rung that does not fit ends the walk rather than being skipped, so the next week picks
   * the ladder up where this one stopped.
   */
  private static ProjectPick pickProjects(Input in, int planned, int dayCount, List<String> notes) {
    List<ProjectQuestion> all = in.projectQuestions();
    if (all.isEmpty()) {
      return new ProjectPick(List.of(), 0);
    }
    int cap = (int) (planned * PROJECT_SHARE);
    LocalDate lastDay = in.weekStart().plusDays(6);
    Map<Long, ProjectQuestion> byId = new HashMap<>();
    all.forEach(q -> byId.put(q.questionId(), q));
    List<TakenQuestion> taken = new ArrayList<>();
    Set<Long> takenIds = new HashSet<>();
    int[] used = {0};
    Predicate<ProjectQuestion> fits = q -> !takenIds.contains(q.questionId()) && taken.size() < dayCount
        && used[0] + q.minutes() <= cap;
    BiConsumer<ProjectQuestion, String> take = (q, why) -> {
      taken.add(new TakenQuestion(q, why));
      takenIds.add(q.questionId());
      used[0] += q.minutes();
    };

    int carriedOut = 0;
    for (Long id : in.carriedQuestions()) {
      ProjectQuestion q = byId.get(id);
      if (q == null || takenIds.contains(id)) {
        continue;  // retired since, or listed twice
      }
      if (fits.test(q)) {
        take.accept(q, CARRIED + ": " + about(q) + ".");
      } else {
        carriedOut++;
      }
    }
    if (carriedOut > 0) {
      notes.add(carriedOut + " unrated project question" + (carriedOut == 1 ? "" : "s") + " from last week did"
          + " not fit in the projects' share; they compete with the rest of your project questions.");
    }

    List<ProjectQuestion> due = all.stream()
        .filter(q -> q.dueOn() != null && !q.dueOn().isAfter(lastDay))
        .sorted(Comparator.comparing(ProjectQuestion::dueOn).thenComparingInt(ProjectQuestion::projectOrder)
            .thenComparingInt(ProjectQuestion::questionOrder))
        .toList();
    int dueLeftOut = 0;
    for (ProjectQuestion q : due) {
      if (fits.test(q)) {
        String when = q.dueOn().isBefore(in.weekStart()) ? "overdue since " + DAY.format(q.dueOn())
            : "due " + DAY.format(q.dueOn());
        take.accept(q, "Rehearse again, " + when + ": " + about(q)
            + ". Answer it aloud before rereading what you wrote.");
      } else if (!takenIds.contains(q.questionId())) {
        dueLeftOut++;
      }
    }

    // New questions: a project already under way first, so a started ladder gets finished.
    Set<Long> started = new HashSet<>();
    all.stream().filter(q -> q.dueOn() != null).forEach(q -> started.add(q.projectId()));
    List<ProjectQuestion> fresh = all.stream().filter(q -> q.dueOn() == null)
        .sorted(Comparator.comparing((ProjectQuestion q) -> !started.contains(q.projectId()))
            .thenComparingInt(ProjectQuestion::projectOrder).thenComparingInt(ProjectQuestion::questionOrder))
        .toList();
    boolean anyNew = false;
    for (ProjectQuestion q : fresh) {
      if (takenIds.contains(q.questionId())) {
        continue;
      }
      if (fits.test(q)) {
        take.accept(q, "Next on the " + q.projectName() + " ladder: " + RUNG.getOrDefault(q.rung(), q.rung())
            + ". Your projects take up to " + Math.round(PROJECT_SHARE * 100) + "% of the week.");
        anyNew = true;
      } else if (anyNew || !taken.isEmpty()) {
        break;
      }
    }

    if (!taken.isEmpty()) {
      notes.add("Questions about your projects take " + used[0] + " minutes, off the top like reviews (up to "
          + Math.round(PROJECT_SHARE * 100) + "% of the week, never two on one day).");
    } else {
      notes.add("No project question fitted this week: " + Math.round(PROJECT_SHARE * 100)
          + "% of it is " + cap + " minutes.");
    }
    if (dueLeftOut > 0) {
      notes.add(dueLeftOut + " project question" + (dueLeftOut == 1 ? "" : "s") + " due for rehearsal did not"
          + " fit; they stay on the home page.");
    }
    return new ProjectPick(List.copyOf(taken), used[0]);
  }

  private static String about(ProjectQuestion q) {
    return RUNG.getOrDefault(q.rung(), q.rung()) + " on " + q.projectName();
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

  /**
   * Each domain's fraction of the week: weight × a weakness factor from 1.5 (rated 0) down to 0.7
   * (rated 5), over the domains with a weight above zero and something to learn. The weights form
   * previews exactly this, so what the learner sees while typing is what the plan will use.
   */
  static Map<String, Double> shareOf(List<Domain> domains, Set<String> withUnits) {
    return normalised(domains, withUnits, d -> 1.5 - 0.16 * Math.clamp(d.strength(), 0, 5));
  }

  /**
   * An area's share for the weights form: {@code percent} is what the plan uses, {@code
   * unboostedPercent} what the weights alone would give (the same areas, no weakness factor), so the
   * form can say when the learner's strength moved an area's share.
   */
  record ShareDetail(String domainId, String name, int percent, int unboostedPercent, double strength) {}

  static List<ShareDetail> shareDetails(List<Domain> domains, Set<String> withUnits) {
    Map<String, Double> share = shareOf(domains, withUnits);
    Map<String, Double> plain = normalised(domains, withUnits, d -> 1.0);
    return domains.stream().filter(d -> share.containsKey(d.id()))
        .map(d -> new ShareDetail(d.id(), d.name(), percent(share.get(d.id())), percent(plain.get(d.id())),
            d.strength()))
        .toList();
  }

  private static int percent(double fraction) {
    return (int) Math.round(fraction * 100);
  }

  private static Map<String, Double> normalised(List<Domain> domains, Set<String> withUnits,
      ToDoubleFunction<Domain> factor) {
    Map<String, Double> raw = new LinkedHashMap<>();
    for (Domain d : domains) {
      if (d.weight() > 0 && withUnits.contains(d.id())) {
        raw.put(d.id(), Math.clamp(d.weight() * factor.applyAsDouble(d), d.weight() * 0.5, d.weight() * 2.0));
      }
    }
    double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
    Map<String, Double> share = new LinkedHashMap<>();
    raw.forEach((id, v) -> share.put(id, v / total));
    return share;
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
