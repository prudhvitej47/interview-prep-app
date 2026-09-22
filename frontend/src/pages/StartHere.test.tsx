import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { StartHere } from "./StartHere";
import { me, mockFetch } from "../testing";

const rewards = (stars: number) => ({
  stars, starsThisWeek: 0, streak: 0, longestStreak: 0, freezes: 1, thisWeekCounts: false,
  recentWeeks: [], milestonesReached: [], nextMilestone: 50,
});
const NEW_WEEK = { weekStart: "2026-09-28", onBreak: false, settings: { hoursPerWeek: null, studyDays: [], weights: {} }, plan: null };
const SET_UP_WEEK = { ...NEW_WEEK, settings: { hoursPerWeek: 8, studyDays: [1, 3, 5], weights: {} } };

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=token-from-the-server";
});
afterEach(() => vi.unstubAllGlobals());

const show = (onMe = vi.fn()) => {
  render(<MemoryRouter><StartHere onMe={onMe} /></MemoryRouter>);
  return onMe;
};

it("walks a new learner through the first week, nothing ticked yet", async () => {
  mockFetch({ "/api/plan": { body: NEW_WEEK }, "/api/rewards": { body: rewards(0) } });
  show();
  const card = await screen.findByRole("region", { name: "Start here" });
  expect(card).toHaveTextContent("Set up your week");
  expect(card).toHaveTextContent("Again, Hard, Good or Easy");
  await waitFor(() => expect(screen.getAllByLabelText("to do")).toHaveLength(2));
  expect(screen.getByRole("link", { name: "How this works" })).toHaveAttribute("href", "/how-it-works");
});

it("ticks the week once hours and days are set", async () => {
  mockFetch({ "/api/plan": { body: SET_UP_WEEK }, "/api/rewards": { body: rewards(0) } });
  show();
  expect(await screen.findByLabelText("done")).toBeInTheDocument();
  expect(screen.getByLabelText("to do")).toBeInTheDocument();
});

it("closes for good when asked, and tells the app", async () => {
  const fetchMock = mockFetch({
    "/api/plan": { body: NEW_WEEK }, "/api/rewards": { body: rewards(0) },
    "/api/me/start-guide": { body: me({ onboarded: true, startGuideClosed: true }) },
  });
  const onMe = show();
  fireEvent.click(await screen.findByRole("button", { name: "Close this guide" }));
  await waitFor(() => expect(onMe).toHaveBeenCalledWith(expect.objectContaining({ startGuideClosed: true })));
  const put = fetchMock.mock.calls.find(([u]) => u === "/api/me/start-guide")!;
  expect(put[1]).toMatchObject({ method: "PUT", body: JSON.stringify({ closed: true }) });
});

it("records itself closed once both steps are done, but stays up for this visit", async () => {
  const fetchMock = mockFetch({
    "/api/plan": { body: SET_UP_WEEK }, "/api/rewards": { body: rewards(3) },
    "/api/me/start-guide": { body: me({ onboarded: true, startGuideClosed: true }) },
  });
  const onMe = show();
  expect(await screen.findByText(/You are set up/)).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.filter(([u]) => u === "/api/me/start-guide")).toHaveLength(1));
  expect(onMe).not.toHaveBeenCalled();
});
