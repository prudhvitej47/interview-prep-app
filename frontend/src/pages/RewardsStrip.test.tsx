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
