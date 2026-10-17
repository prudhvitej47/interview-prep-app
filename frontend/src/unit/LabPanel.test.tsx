// src/unit/LabPanel.test.tsx
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LabPanel } from "./LabPanel";
import { lastCallTo, mockFetch } from "../testing";

const UNIT = "spring.data.jpa.n-plus-one-and-lazy-loading";
const BASE = `/api/units/${UNIT}/lab`;
const LAB = { title: "Count the queries", minutes: 20, needs: ["jdk", "jbang"], readme: "## 1. Get the files\nRun it." };
const DONE = "2026-10-17T06:30:00Z";
const shownDate = new Date(DONE).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

beforeEach(() => { document.cookie = "XSRF-TOKEN=t"; });
afterEach(() => vi.unstubAllGlobals());

it("shows what the lab needs, its steps and the starter download", async () => {
  mockFetch({ [BASE]: { body: { doneAt: null } } });
  render(<LabPanel unitId={UNIT} lab={LAB} />);
  expect(screen.getByRole("heading", { name: "Lab: Count the queries" })).toBeInTheDocument();
  expect(screen.getByText("About 20 min on your laptop · needs JDK 25, JBang")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Download the starter (.zip)" })).toHaveAttribute("href", `${BASE}/starter.zip`);
  expect(screen.getByText("Show the lab steps")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Check" })).toBeDisabled();
});

it("accepts the whole TOKEN line, checks it on the server and shows the lab as done", async () => {
  const fetchMock = mockFetch({
    [BASE]: { body: { doneAt: null } },
    [`${BASE}/check`]: { body: { right: true, doneAt: DONE } },
  });
  render(<LabPanel unitId={UNIT} lab={LAB} />);
  fireEvent.change(screen.getByLabelText("Token from LabCheck"), { target: { value: "TOKEN: 70E1AB80" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(await screen.findByText(`✓ Lab done ${shownDate}`)).toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent("Right: the lab is done.");
  expect(JSON.parse(lastCallTo(fetchMock, `${BASE}/check`)[1]!.body as string)).toEqual({ token: "70e1ab80" });
  expect(document.activeElement).toBe(screen.getByRole("status"));
});

it("says so when the token does not match, and keeps Check off for text that cannot be a token", async () => {
  mockFetch({
    [BASE]: { body: { doneAt: null } },
    [`${BASE}/check`]: { body: { right: false, doneAt: null } },
  });
  render(<LabPanel unitId={UNIT} lab={LAB} />);
  const box = screen.getByLabelText("Token from LabCheck");
  fireEvent.change(box, { target: { value: "hello" } });
  expect(screen.getByRole("button", { name: "Check" })).toBeDisabled();
  fireEvent.change(box, { target: { value: "deadbeef" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(await screen.findByText(/That token does not match/)).toBeInTheDocument();
});

it("shows a lab already done on a revisit", async () => {
  mockFetch({ [BASE]: { body: { doneAt: DONE } } });
  render(<LabPanel unitId={UNIT} lab={LAB} />);
  expect(await screen.findByText(`✓ Lab done ${shownDate}`)).toBeInTheDocument();
});

it("keeps a fresh Lab done when the first status answer arrives late", async () => {
  let answerStatus: (v: unknown) => void = () => {};
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url === BASE) {
      return new Promise((resolve) => { answerStatus = resolve; });
    }
    return Promise.resolve({ ok: true, status: 200, json: async () => ({ right: true, doneAt: DONE }) });
  }));
  render(<LabPanel unitId={UNIT} lab={LAB} />);
  fireEvent.change(screen.getByLabelText("Token from LabCheck"), { target: { value: "70e1ab80" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  expect(await screen.findByText(`✓ Lab done ${shownDate}`)).toBeInTheDocument();
  answerStatus({ ok: true, status: 200, json: async () => ({ doneAt: null }) });
  await new Promise((r) => setTimeout(r, 0));
  expect(screen.getByText(`✓ Lab done ${shownDate}`)).toBeInTheDocument();
});
