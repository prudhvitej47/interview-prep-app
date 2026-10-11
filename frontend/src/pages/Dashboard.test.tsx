import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { Dashboard } from "./Dashboard";
import { mockFetch } from "../testing";

const dashboard = {
  coverage: [{ domainId: "dsa", name: "DSA", done: 1, total: 4,
    stages: { notStarted: 3, learned: 0, reviewDue: 1, solid: 0 } }],
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

it("shows this week, a line of coverage, weak areas and what changed", async () => {
  mockFetch({ "/api/dashboard": { body: dashboard }, "/api/plan": { body: week } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const thisWeek = await screen.findByRole("region", { name: "This week" });
  expect(thisWeek).toHaveTextContent("20 of 80 goal minutes done");
  expect(within(thisWeek).getByRole("link", { name: "Sliding window" })).toBeInTheDocument();
  expect(screen.getByText(/1 of 4 units started across 1 area, 1 due for review\./)).toBeInTheDocument();
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

it("lists a planned project question beside the units, linked to its project", async () => {
  const question = { unitId: null, title: "Why double entry?", type: "project-question", day: 7, kind: "project",
    minutes: 15, reason: "", done: false, projectQuestionId: 13, projectId: 3, projectName: "Example ledger",
    rung: "why" };
  mockFetch({ "/api/dashboard": { body: dashboard },
    "/api/plan": { body: { ...week, plan: { ...week.plan, items: [...week.plan.items, question] } } } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const thisWeek = await screen.findByRole("region", { name: "This week" });
  expect(within(thisWeek).getByRole("link", { name: "Why double entry?" })).toHaveAttribute("href", "/projects/3#q-13");
  expect(thisWeek).toHaveTextContent("Your project: Example ledger · Why this way · 15 min");
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

const many = (n: number, chosen = false) => Array.from({ length: n }, (_, i) => ({
  unitId: `u${i + 1}`, title: `Unit ${i + 1}`, type: "concept", added: true,
  placement: i % 2 ? "now" : "next-week", suggested: i % 2 ? "now" : "next-week", chosen,
}));
const withUnits = (units: unknown[]) => ({ ...dashboard, whatChanged: { ...dashboard.whatChanged, units } });

it("shows the first ten new units and the rest on request", async () => {
  mockFetch({ "/api/dashboard": { body: withUnits(many(25)) }, "/api/plan": { body: week } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const changed = await screen.findByRole("region", { name: "What changed" });
  expect(within(changed).getAllByRole("group", { name: /When to start/ })).toHaveLength(10);
  expect(within(changed).queryByRole("link", { name: "Unit 11" })).not.toBeInTheDocument();
  fireEvent.click(within(changed).getByRole("button", { name: "Show all 25" }));
  expect(within(changed).getAllByRole("group", { name: /When to start/ })).toHaveLength(25);
  expect(within(changed).queryByRole("button", { name: /Show all/ })).not.toBeInTheDocument();
});

it("has no show-all control when ten or fewer units arrived", async () => {
  mockFetch({ "/api/dashboard": { body: withUnits(many(10)) }, "/api/plan": { body: week } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const changed = await screen.findByRole("region", { name: "What changed" });
  expect(within(changed).getAllByRole("group", { name: /When to start/ })).toHaveLength(10);
  expect(within(changed).queryByRole("button", { name: /Show all/ })).not.toBeInTheDocument();
});

it("accepts every suggested placement in one request, including rows not shown yet", async () => {
  const units = many(12);
  units[0] = { ...units[0], placement: "end-of-track", chosen: true };
  const fetchMock = mockFetch({ "/api/dashboard": { body: withUnits(units) }, "/api/plan": { body: week },
    "/api/placements/accept-suggested": { body: withUnits(many(12, true)) } });
  render(<MemoryRouter><Dashboard /></MemoryRouter>);
  const changed = await screen.findByRole("region", { name: "What changed" });
  fireEvent.click(within(changed).getByRole("button", { name: "Accept all 11 suggested placements" }));
  // Done: every row is chosen, so the suggestions and the button go.
  await waitFor(() => expect(changed).not.toHaveTextContent("suggested:"));
  expect(within(changed).queryByRole("button", { name: /Accept/ })).not.toBeInTheDocument();
  const calls = fetchMock.mock.calls.filter(([u]) => u === "/api/placements/accept-suggested");
  expect(calls).toHaveLength(1);
  expect(calls[0][1]!.method).toBe("POST");
  // The row the learner already chose for is left out; the two not shown yet are in.
  expect(JSON.parse(calls[0][1]!.body as string)).toEqual({ unitIds: units.slice(1).map((u) => u.unitId) });
  expect(calls[0][1]!.headers).toMatchObject({ "X-XSRF-TOKEN": "token-from-the-server" });
});
