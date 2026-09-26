import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WeekPage } from "./WeekPage";
import { DOMAINS, mockFetch } from "../testing";

const NO_SETTINGS = { hoursPerWeek: null, studyDays: [], weights: {} };
const SETTINGS = { hoursPerWeek: 9, studyDays: [1, 3], weights: {} };

const plan = {
  plannedMinutes: 486,
  goalMinutes: 389,
  doneMinutes: 25,
  items: [
    { unitId: "dsa.window.concept", title: "Sliding window", type: "concept", day: 1, kind: "learn", minutes: 25,
      reason: "Required every week: DSA; DSA is 30% of this week; you are at 2.0/5 in Sliding window.", done: true },
    { unitId: "db.sql.joins", title: "Unsettled payments", type: "sql", day: 3, kind: "learn", minutes: 20,
      reason: "Databases is 12% of this week.", done: false },
    { unitId: "ds.idem", title: "Idempotency keys", type: "concept", day: 3, kind: "review", minutes: 15,
      reason: "Review due Wed 30 Sep. Recall it before reopening it.", done: false },
  ],
  shares: [{ domainId: "dsa", name: "DSA", percent: 30 }],
  notes: ["No system design units to learn yet, so this week has none."],
  topicsToRate: [{ topicId: "db.sql", name: "SQL", domainName: "Databases", currentGuess: 3 }],
};

const week = (p: object | null, settings: object = SETTINGS, onBreak = false) =>
  ({ weekStart: "2026-09-28", settings, plan: p, onBreak });

// Dates show in the browser's own locale ("Mon, 28 Sept" here, "Mon, Sep 28" on a US machine such
// as CI), so expected headings are formatted the same way rather than hard-coded.
const heading = (year: number, month: number, day: number) =>
  new Date(year, month - 1, day).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });

function show() {
  render(<MemoryRouter><WeekPage /></MemoryRouter>);
}

describe("WeekPage", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("asks for hours and days first, then plans the week", async () => {
    const fetchMock = mockFetch({
      "/api/plan": { body: week(null, NO_SETTINGS) },
      "/api/domains": { body: DOMAINS },
      "/api/me/week": { body: SETTINGS },
    });
    show();
    const button = await screen.findByRole("button", { name: "Plan my week" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Hours a week"), { target: { value: "9" } });
    fireEvent.click(screen.getByLabelText("Mon"));
    fireEvent.click(screen.getByLabelText("Wed"));
    expect(button).toBeEnabled();

    // After saving, the next /api/plan call returns the new plan.
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => ({
      ok: true, status: 200,
      json: async () => (url === "/api/plan" && !init?.method ? week(plan) : SETTINGS),
    }));
    fireEvent.click(button);

    expect(await screen.findByRole("link", { name: "Sliding window" })).toBeInTheDocument();
    const put = fetchMock.mock.calls.find(([u, i]) => u === "/api/me/week" && i?.method === "PUT")!;
    expect(JSON.parse(put[1]!.body as string)).toEqual({ hoursPerWeek: 9, studyDays: [1, 3], weights: {} });
    expect(put[1]!.headers).toMatchObject({ "X-XSRF-TOKEN": "token-from-the-server" });
    expect(fetchMock.mock.calls.some(([u, i]) => u === "/api/plan" && i?.method === "DELETE")).toBe(true);
  });

  it("shows the share of the week each weight gives, as the planner computes it", async () => {
    const fetchMock = mockFetch({
      "/api/plan": { body: week(null, NO_SETTINGS) },
      "/api/domains": { body: DOMAINS },
      "/api/plan/shares": { body: [{ domainId: "dsa", name: "DSA and coding", percent: 100 }] },
    });
    show();
    const dsa = await screen.findByLabelText("Weight for DSA and coding");
    fireEvent.change(screen.getByLabelText("Weight for Databases and SQL"), { target: { value: "0" } });
    await waitFor(() => expect(dsa.closest("label")).toHaveTextContent("≈ 100% of your week"));
    expect(screen.getByLabelText("Weight for Databases and SQL").closest("label")).toHaveTextContent("left out");
    const sent = fetchMock.mock.calls.filter(([url]) => url === "/api/plan/shares").at(-1);
    expect(JSON.parse(String(sent?.[1]?.body))).toEqual({ weights: { databases: 0 } });
  });

  it("lays the week out by day, with why each item is there and what is done", async () => {
    mockFetch({ "/api/plan": { body: week(plan) } });
    show();
    const monday = (await screen.findByRole("heading", { name: heading(2026, 9, 28) })).closest("section")!;
    expect(within(monday).getByLabelText("done")).toBeInTheDocument();
    expect(monday).toHaveTextContent("Required every week: DSA");
    const wednesday = screen.getByRole("heading", { name: heading(2026, 9, 30) }).closest("section")!;
    expect(wednesday).toHaveTextContent("Review · 15 min");
    expect(screen.getByText(/25 of 389 goal minutes done/)).toBeInTheDocument();
    expect(screen.getByText("No system design units to learn yet, so this week has none.")).toBeInTheDocument();
  });

  it("offers to sharpen guessed topics without blocking anything", async () => {
    const fetchMock = mockFetch({ "/api/plan": { body: week(plan) }, "/api/me/ratings/topics": { body: {} } });
    show();
    const card = (await screen.findByRole("heading", { name: "How are you with these?" })).closest("section")!;
    expect(card).toHaveTextContent("guessed 3/5");
    fireEvent.click(within(card).getByLabelText("1"));
    fireEvent.click(within(card).getByRole("button", { name: "Save ratings" }));
    expect(await screen.findByText(/Next week's plan will use these/)).toBeInTheDocument();
    const [, init] = fetchMock.mock.calls.at(-1)!;
    expect(JSON.parse(init!.body as string)).toEqual({ ratings: { "db.sql": 1 } });
  });

  it("says so on a planned break, and plans nothing", async () => {
    mockFetch({ "/api/plan": { body: week(null, SETTINGS, true) } });
    show();
    expect(await screen.findByRole("note")).toHaveTextContent("planned break");
    expect(screen.queryByRole("button", { name: "Plan my week" })).not.toBeInTheDocument();
  });

  it("says when the goal is met", async () => {
    mockFetch({ "/api/plan": { body: week({ ...plan, doneMinutes: 400 }) } });
    show();
    expect(await screen.findByText(/Goal met: this week counts towards your streak/)).toBeInTheDocument();
  });

  it("takes a future week off", async () => {
    const breaks = { thisWeek: false, upcoming: [
      { weekStart: "2026-10-05", taken: false, available: true },
      { weekStart: "2026-10-12", taken: false, available: false },
    ] };
    const fetchMock = mockFetch({ "/api/plan": { body: week(plan) }, "/api/breaks": { body: breaks } });
    show();
    const section = (await screen.findByText("Planned breaks")).closest("details")!;
    expect(within(section as HTMLElement).getByText("two already taken this quarter")).toBeInTheDocument();
    fetchMock.mockImplementationOnce(async () => ({ ok: true, status: 200, json: async () => ({
      ...breaks, upcoming: [{ ...breaks.upcoming[0], taken: true }, breaks.upcoming[1]] }) }));
    fireEvent.click(within(section as HTMLElement).getByRole("button", { name: "Take it off" }));
    expect(await within(section as HTMLElement).findByRole("button", { name: "Give it back" })).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(url).toBe("/api/breaks");
    expect(JSON.parse(init!.body as string)).toEqual({ weekStart: "2026-10-05" });
  });
});
