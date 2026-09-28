import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProjectPage } from "./ProjectPage";
import { ReviewsDue } from "./ReviewsDue";
import { mockFetch } from "../testing";

// Everything here is invented: this repository is public.
const NOT_RATED = { attempts: 0, firstDoneAt: null, lastAt: null, lastRating: null, dueOn: null, reviewDue: false };
const RATED = {
  attempts: 1,
  firstDoneAt: "2026-09-20T10:00:00Z",
  lastAt: "2026-09-20T10:00:00Z",
  lastRating: "good",
  dueOn: "2026-09-21",
  reviewDue: true,
};

const question = (overrides: object = {}) => ({
  id: 11,
  rung: "scale",
  prompt: "What fails first at ten times the load?",
  probes: "And the hot account?",
  strongAnswer: "Names the **bottleneck** first.",
  units: [{ id: "ds.transactions.idempotency", title: "Idempotency keys" }],
  minutes: 15,
  answer: "",
  answeredAt: null,
  progress: NOT_RATED,
  ...overrides,
});

const project = (questions: object[]) => ({
  id: 1,
  name: "Example ledger",
  summary: "Built the posting service.",
  topics: ["hld.payments"],
  questions,
});

function show(routes: Record<string, { status?: number; body?: unknown }>, at = "/projects/1") {
  const fetchMock = mockFetch(routes);
  render(
    <MemoryRouter initialEntries={[at]}>
      <Routes>
        <Route path="/projects/:projectId" element={<ProjectPage />} />
      </Routes>
    </MemoryRouter>,
  );
  return fetchMock;
}

describe("ProjectPage", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the summary and each question with its rung, answer box and the units underneath", async () => {
    show({ "/api/projects/1": { body: project([question()]) } });
    expect(await screen.findByRole("heading", { level: 2, name: "Example ledger" })).toBeInTheDocument();
    expect(screen.getByText("Built the posting service.")).toBeInTheDocument();
    expect(screen.getByText("1. At scale · 15 min")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "What fails first at ten times the load?" })).toBeInTheDocument();
    expect(screen.getByLabelText("Your answer")).toHaveValue("");
    expect(screen.getByRole("link", { name: "Idempotency keys" })).toHaveAttribute("href", "/units/ds.transactions.idempotency");
    expect(screen.getByRole("link", { name: "My projects" })).toHaveAttribute("href", "/projects");
  });

  it("keeps the follow-ups and the strong answer out of reach until there is an answer or a rating", async () => {
    show({ "/api/projects/1": { body: project([question()]) } });
    await screen.findByRole("heading", { level: 2, name: "Example ledger" });
    expect(screen.queryByText("And the hot account?")).not.toBeInTheDocument();
    expect(screen.queryByText("Show what a strong answer covers")).not.toBeInTheDocument();
    expect(screen.getByText(/open once you have written an answer or rated this question/)).toBeInTheDocument();
  });

  it("folds them, ready to open, once an answer has been written", async () => {
    show({ "/api/projects/1": { body: project([question({ answer: "The single hot row.", answeredAt: "2026-09-20T10:00:00Z" })]) } });
    const strong = await screen.findByText("bottleneck");
    expect(strong.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("Show the follow-ups")).toBeInTheDocument();
    expect(screen.getByText("Show what a strong answer covers")).toBeInTheDocument();
  });

  it("unfolds them after a rating, and the rating can be undone", async () => {
    const fetchMock = show({
      "/api/projects/1": { body: project([question()]) },
      "/api/projects/questions/11/attempts": { body: { ...RATED, reviewDue: false, lastRating: "again" } },
      "/api/projects/questions/11/attempts/latest": { body: NOT_RATED },
    });
    await screen.findByRole("heading", { level: 2, name: "Example ledger" });
    const panel = screen.getByRole("region", { name: "How the rehearsal went" });
    fireEvent.click(within(panel).getByRole("button", { name: /Again/ }));

    expect(await screen.findByText("Show what a strong answer covers")).toBeInTheDocument();
    const rated = fetchMock.mock.calls.find(([url]) => url === "/api/projects/questions/11/attempts")!;
    expect(rated[1]).toMatchObject({ method: "POST", body: JSON.stringify({ rating: "again" }) });

    fireEvent.click(within(panel).getByRole("button", { name: "Undo: not rehearsed after all" }));
    await waitFor(() => expect(screen.queryByText("Show what a strong answer covers")).not.toBeInTheDocument());
  });

  it("asks for a rehearsal when one is due", async () => {
    show({ "/api/projects/1": { body: project([question({ progress: RATED })]) } });
    expect(await screen.findByRole("heading", { level: 4, name: "Review due" })).toBeInTheDocument();
    expect(screen.getByText(/Answer it again out loud, from memory/)).toBeInTheDocument();
  });

  it("saves the answer when the box is left, and then unfolds", async () => {
    const fetchMock = show({
      "/api/projects/1": { body: project([question()]) },
      "/api/projects/questions/11/answer": { body: { answer: "The single hot row.", answeredAt: "2026-09-28T10:00:00Z" } },
    });
    const box = await screen.findByLabelText("Your answer");
    fireEvent.change(box, { target: { value: "The single hot row." } });
    expect(screen.getByText("Not saved yet")).toBeInTheDocument();
    fireEvent.blur(box);

    expect(await screen.findByText("Show the follow-ups")).toBeInTheDocument();
    const saved = fetchMock.mock.calls.find(([url]) => url === "/api/projects/questions/11/answer")!;
    expect(saved[1]).toMatchObject({ method: "PUT", body: JSON.stringify({ answer: "The single hot row." }) });
    expect(screen.getByText(/^Saved .*Only you can see it\.$/)).toBeInTheDocument();
  });

  it("saves the answer by itself once typing pauses", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const fetchMock = show({
        "/api/projects/1": { body: project([question()]) },
        "/api/projects/questions/11/answer": { body: { answer: "Draft.", answeredAt: "2026-09-28T10:00:00Z" } },
      });
      fireEvent.change(await screen.findByLabelText("Your answer"), { target: { value: "Draft." } });
      expect(fetchMock.mock.calls.map(([url]) => url)).not.toContain("/api/projects/questions/11/answer");
      await vi.advanceTimersByTimeAsync(1600);
      await waitFor(() =>
        expect(fetchMock.mock.calls.map(([url]) => url)).toContain("/api/projects/questions/11/answer"));
    } finally {
      vi.useRealTimers();
    }
  });

  it("says so when there is no such project", async () => {
    show({ "/api/projects/9": { status: 404 } }, "/projects/9");
    expect(await screen.findByRole("alert")).toHaveTextContent("There is no such project.");
  });
});

describe("ReviewsDue", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("lists due project questions under their own heading", async () => {
    mockFetch({
      "/api/reviews": { body: { due: [], nextDueOn: null } },
      "/api/projects/reviews": {
        body: {
          due: [{ questionId: 11, projectId: 1, projectName: "Example gateway", rung: "failure",
            prompt: "What happens when the router dies mid-request?", minutes: 15, dueOn: "2026-09-27" }],
          nextDueOn: null,
        },
      },
    });
    render(<MemoryRouter><ReviewsDue /></MemoryRouter>);
    expect(await screen.findByRole("heading", { name: /^Project questions due\s*1$/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "What happens when the router dies mid-request?" }))
      .toHaveAttribute("href", "/projects/1#q-11");
    expect(screen.getByText(/Example gateway · When it fails · due/)).toBeInTheDocument();
    // No unit has been rated, so the unit reviews say nothing at all.
    expect(screen.queryByRole("heading", { name: /^Reviews due/ })).not.toBeInTheDocument();
  });
});
