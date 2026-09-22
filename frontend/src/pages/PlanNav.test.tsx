import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { PlanNav } from "./PlanNav";
import { mockFetch } from "../testing";

const item = (unitId: string, title: string, day: number) =>
  ({ unitId, title, type: "concept", day, kind: "learn", minutes: 30, reason: "", done: false });

const week = (items: object[]) => ({
  weekStart: "2026-09-28", onBreak: false, settings: { hoursPerWeek: 8, studyDays: [1, 2], weights: {} },
  plan: { plannedMinutes: 90, goalMinutes: 72, doneMinutes: 0, shares: [], notes: [], topicsToRate: [], items },
});

afterEach(() => vi.unstubAllGlobals());

const show = (unitId: string, items: object[]) => {
  mockFetch({ "/api/plan": { body: week(items) } });
  render(<MemoryRouter><PlanNav unitId={unitId} /></MemoryRouter>);
};

it("offers the unit before and after, in the plan's own order", async () => {
  show("dsa.trees.path-sum", [item("dsa.trees.pattern", "Trees", 1), item("dsa.trees.path-sum", "Path sum", 2),
    item("hld.job-scheduler", "Job scheduler", 3)]);
  const nav = await screen.findByRole("navigation", { name: "This week's plan" });
  expect(nav).toHaveTextContent("← Trees");
  expect(nav).toHaveTextContent("Job scheduler →");
  expect(screen.getByRole("link", { name: /Job scheduler/ })).toHaveAttribute("href", "/units/hld.job-scheduler");
  expect(screen.getByRole("link", { name: "This week's plan (2 of 3)" })).toHaveAttribute("href", "/week");
});

it("says so at the end of the week", async () => {
  show("b", [item("a", "First", 1), item("b", "Second", 2)]);
  expect(await screen.findByText("Last in the week")).toBeInTheDocument();
});

it("shows nothing for a unit that is not in this week's plan", async () => {
  show("elsewhere", [item("a", "First", 1)]);
  await new Promise((r) => setTimeout(r, 0));
  expect(screen.queryByRole("navigation")).toBeNull();
});
