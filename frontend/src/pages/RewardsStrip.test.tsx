import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { RewardsStrip } from "./RewardsStrip";
import { mockFetch } from "../testing";

afterEach(() => vi.unstubAllGlobals());

it("shows stars, the streak, freezes and a quiet mark per week", async () => {
  mockFetch({ "/api/rewards": { body: {
    stars: 52, starsThisWeek: 3, streak: 2, longestStreak: 4, freezes: 1, thisWeekCounts: false,
    recentWeeks: [
      { weekStart: "2026-10-05", outcome: "GOAL_MET" },
      { weekStart: "2026-10-12", outcome: "FREEZE_USED" },
      { weekStart: "2026-10-19", outcome: "BREAK" },
      { weekStart: "2026-10-26", outcome: "IN_PROGRESS" },
    ],
    milestonesReached: [50], nextMilestone: 100,
  } } });
  render(<RewardsStrip />);
  const strip = await screen.findByRole("region", { name: "Stars and streak" });
  expect(strip).toHaveTextContent("★ 52 stars (+3 this week) · 2-week streak · 1 freeze banked");
  expect(screen.getByLabelText("Recent weeks")).toHaveTextContent("●❄–◌");
  // Each mark shown is explained in words, so the in-progress mark never stands alone.
  expect(strip).toHaveTextContent("◌ this week, in progress");
  expect(screen.getByLabelText(/: missed, a freeze kept the streak$/)).toBeInTheDocument();
  expect(strip).toHaveTextContent("You have passed 50 stars.");
});

const strip = (recentWeeks: { weekStart: string; outcome: string }[]) => {
  mockFetch({ "/api/rewards": { body: {
    stars: 4, starsThisWeek: 1, streak: 1, longestStreak: 1, freezes: 0, thisWeekCounts: false,
    recentWeeks, milestonesReached: [], nextMilestone: 50,
  } } });
  render(<RewardsStrip />);
  return screen.findByRole("region", { name: "Stars and streak" });
};

it("says a single week in words, with no mark and no key to decode", async () => {
  const region = await strip([{ weekStart: "2026-10-26", outcome: "IN_PROGRESS" }]);
  expect(region).toHaveTextContent("This week: in progress");
  expect(region).not.toHaveTextContent("◌");
  expect(screen.queryByLabelText("Recent weeks")).not.toBeInTheDocument();
});

it("names the week when the one week on record is already finished", async () => {
  const region = await strip([{ weekStart: "2026-10-19", outcome: "GOAL_MET" }]);
  expect(region).toHaveTextContent("Week of Mon, Oct 19: goal met");
  expect(region).not.toHaveTextContent("●");
});

it("keeps one row of marks and one key however many weeks there are", async () => {
  const weeks = Array.from({ length: 12 }, (_, i) => ({
    weekStart: `2026-0${i < 4 ? 7 : 8}-0${(i % 4) + 1}`,
    outcome: i === 11 ? "IN_PROGRESS" : "GOAL_MET",
  }));
  const region = await strip(weeks);
  expect(screen.getByLabelText("Recent weeks")).toHaveTextContent("●●●●●●●●●●●◌");
  // Twelve weeks, two outcomes: the key explains each mark once, not once per week.
  expect(region).toHaveTextContent("● goal met · ◌ this week, in progress");
});
