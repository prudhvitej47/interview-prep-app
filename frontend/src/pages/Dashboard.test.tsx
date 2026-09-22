import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { Dashboard } from "./Dashboard";
import { mockFetch } from "../testing";

const dashboard = {
  coverage: [{ domainId: "dsa", name: "DSA", done: 1, total: 4 }],
  weakAreas: [{ topicId: "ds.locks", name: "Locks and leases", domainName: "Distributed", strength: 1.5, unitsLeft: 2 }],
  whatChanged: {
    version: "2026.40.1", releasedAt: "2026-09-27T20:00:00Z", changelog: "# Job scheduler\n\nFrom a new report.",
    added: 1, changed: 0, retired: 0,
    units: [{ unitId: "hld.job-scheduler", title: "Job scheduler", type: "hld", added: true,
      placement: "next-week", suggested: "next-week", chosen: false }],
  },
};

// Sunday, so "next up" exists whatever day the test runs.
const week = { weekStart: "2026-09-28", onBreak: false, settings: { hoursPerWeek: 9, studyDays: [7], weights: {} },
  plan: { plannedMinutes: 100, goalMinutes: 80, doneMinutes: 20, shares: [], notes: [], topicsToRate: [],
    items: [{ unitId: "dsa.window", title: "Sliding window", type: "concept", day: 7, kind: "learn", minutes: 30,
      reason: "", done: false }] } };

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=token-from-the-server";
});
afterEach(() => vi.unstubAllGlobals());

it("shows this week, coverage, weak areas and what changed", async () => {
  mockFetch({ "/api/dashboard": { body: dashboard }, "/api/plan": { body: week } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const thisWeek = await screen.findByRole("region", { name: "This week" });
  expect(thisWeek).toHaveTextContent("20 of 80 goal minutes done");
  expect(within(thisWeek).getByRole("link", { name: "Sliding window" })).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Coverage" })).toHaveTextContent("DSA1/4");
  expect(screen.getByRole("link", { name: "Locks and leases" })).toHaveAttribute("href", "/topics/ds.locks");
  const changed = screen.getByRole("region", { name: "What changed" });
  expect(changed).toHaveTextContent("What changed in 2026.40.1");
  expect(within(changed).getByRole("button", { name: "Next week" })).toHaveAttribute("aria-pressed", "true");
  expect(changed).toHaveTextContent("System design · new · suggested: Next week");
  // 20:00 UTC on the 27th is already the 28th in India, where the tests run.
  expect(changed).toHaveTextContent(new Date("2026-09-27T20:00:00Z").toLocaleDateString(undefined,
    { weekday: "short", day: "numeric", month: "short" }));
  expect(changed).toHaveTextContent(/28/);
});

it("lets the learner start a new unit now", async () => {
  const fetchMock = mockFetch({ "/api/dashboard": { body: dashboard }, "/api/plan": { body: week }, "/api/placements": {
    body: { ...dashboard, whatChanged: { ...dashboard.whatChanged,
      units: [{ ...dashboard.whatChanged.units[0], placement: "now", chosen: true }] } } } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const group = await screen.findByRole("group", { name: "When to start Job scheduler" });
  fireEvent.click(within(group).getByRole("button", { name: "Now" }));
  expect(await within(group).findByRole("button", { name: "Now", pressed: true })).toBeInTheDocument();
  const put = fetchMock.mock.calls.find(([u]) => u === "/api/placements")!;
  expect(JSON.parse(put[1]!.body as string)).toEqual({ unitId: "hld.job-scheduler", choice: "now" });
  expect(put[1]!.headers).toMatchObject({ "X-XSRF-TOKEN": "token-from-the-server" });
});

it("points a learner without settings to their week", async () => {
  mockFetch({ "/api/dashboard": { body: { coverage: [], weakAreas: [], whatChanged: null } },
    "/api/plan": { body: { ...week, plan: null } } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  expect(await screen.findByRole("link", { name: "Tell the planner how much time you have" }))
    .toHaveAttribute("href", "/week");
});
