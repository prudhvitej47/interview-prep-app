import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import { ProgressPage } from "./ProgressPage";
import { TopicPage } from "./TopicPage";
import { StageChip } from "./stages";
import { mockFetch } from "../testing";

// Invented units and projects; this repository is public.
const unit = (unitId: string, domainId: string, stage: string, reviews: number, dueOn: string | null,
  notForMe = false) => ({
  unitId, title: `Title of ${unitId}`, type: "concept", topicId: `${domainId}.topic`, topicName: "Some topic",
  domainId, stage, reviews, dueOn, notForMe,
});

const view = {
  today: "2026-09-28",
  areas: [
    { domainId: "dsa", name: "DSA", stages: { notStarted: 1, learned: 1, reviewDue: 1, solid: 1 } },
    { domainId: "databases", name: "Databases", stages: { notStarted: 0, learned: 1, reviewDue: 0, solid: 0 } },
  ],
  units: [
    unit("dsa.a", "dsa", "solid", 3, "2026-10-14"),
    unit("dsa.b", "dsa", "learned", 2, "2026-10-01"),
    unit("dsa.c", "dsa", "review-due", 1, "2026-09-26"),
    unit("dsa.d", "dsa", "not-started", 0, null),
    unit("db.a", "databases", "learned", 0, "2026-09-29"),
    unit("db.hidden", "databases", "not-started", 0, null, true),
  ],
  notForMe: 1,
  projects: [{
    projectId: 3, name: "Example ledger", stages: { notStarted: 1, learned: 0, reviewDue: 1, solid: 0 },
    questions: [
      { questionId: 12, rung: "why", prompt: "Why double entry?", stage: "review-due", reviews: 0, dueOn: "2026-09-28" },
      { questionId: 13, rung: "scale", prompt: "What fails first at 10x?", stage: "not-started", reviews: 0, dueOn: null },
    ],
  }],
};

afterEach(() => vi.unstubAllGlobals());

function Where() {
  const { search } = useLocation();
  return <output aria-label="search">{search}</output>;
}

function renderPage(at = "/progress") {
  mockFetch({ "/api/progress": { body: view } });
  render(
    <MemoryRouter initialEntries={[at]}>
      <Routes><Route path="/progress" element={<><ProgressPage /><Where /></>} /></Routes>
    </MemoryRouter>,
  );
}

const unitList = () => within(screen.getByRole("region", { name: "Units" })).getByRole("list");
const titles = () => within(unitList()).getAllByRole("link").map((a) => a.textContent);

it("shows each area as a stage bar with its counts in words, and says what it leaves out", async () => {
  renderPage();
  const areas = await screen.findByRole("region", { name: "By area" });
  expect(within(areas).getByRole("img", { name: "DSA: 1 solid, 1 learned, 1 review due, 1 not started" }))
    .toBeInTheDocument();
  expect(areas).toHaveTextContent("DSA3/4");
  expect(areas).toHaveTextContent("1 unit you kept out of your plans (\"not for me\") is left out here.");
  // The "not for me" unit is not listed, whatever the filter.
  expect(titles()).not.toContain("Title of db.hidden");
  expect(titles()).toHaveLength(5);
});

it("filters the units by stage and by area, and keeps the filter in the address", async () => {
  renderPage();
  await screen.findByRole("region", { name: "Units" });
  const stage = screen.getByRole("combobox", { name: "Stage" });
  expect(within(stage).getByRole("option", { name: "Learned (2)" })).toBeInTheDocument();
  fireEvent.change(stage, { target: { value: "learned" } });
  expect(titles()).toEqual(["Title of dsa.b", "Title of db.a"]);
  expect(screen.getByLabelText("search")).toHaveTextContent("?stage=learned");

  fireEvent.change(screen.getByRole("combobox", { name: "Area" }), { target: { value: "databases" } });
  expect(titles()).toEqual(["Title of db.a"]);
  // Counts in the stage menu follow the area.
  expect(within(stage).getByRole("option", { name: "All (1)" })).toBeInTheDocument();

  fireEvent.change(stage, { target: { value: "solid" } });
  expect(within(screen.getByRole("region", { name: "Units" })).getByText("No units match.")).toBeInTheDocument();
});

it("opens with the filter from the address and shows when each unit is next due", async () => {
  renderPage("/progress?stage=review-due");
  await screen.findByRole("region", { name: "Units" });
  expect(titles()).toEqual(["Title of dsa.c"]);
  expect(unitList()).toHaveTextContent("Review due");
  expect(unitList()).toHaveTextContent(/due since/);
  expect(within(unitList()).getByRole("link")).toHaveAttribute("href", "/units/dsa.c");
});

it("lists project questions by project with the same stages, linked to the question", async () => {
  renderPage();
  const section = await screen.findByRole("region", { name: "Project questions" });
  expect(within(section).getByRole("link", { name: "Example ledger" })).toHaveAttribute("href", "/projects/3");
  expect(within(section).getByRole("img", { name: "Example ledger: 0 solid, 0 learned, 1 review due, 1 not started" }))
    .toBeInTheDocument();
  expect(within(section).getByRole("link", { name: "Why double entry?" })).toHaveAttribute("href", "/projects/3#q-12");
  expect(section).toHaveTextContent("Why this way · due today");
  expect(section).toHaveTextContent("What fails first at 10x?Not started");
});

it("names each stage in words on its chip", () => {
  const { rerender } = render(<StageChip stage="not-started" reviews={0} />);
  expect(screen.getByText("Not started")).toBeInTheDocument();
  rerender(<StageChip stage="learned" reviews={0} />);
  expect(screen.getByText("Learned")).toBeInTheDocument();
  rerender(<StageChip stage="learned" reviews={2} />);
  // "×2" is for the eye; a screen reader hears "Reviewed 2 times".
  expect(screen.getByText(/Reviewed/)).toHaveTextContent("Reviewed ×22 times");
  expect(screen.getByText("×2")).toHaveAttribute("aria-hidden", "true");
  rerender(<StageChip stage="learned" reviews={1} />);
  expect(screen.getByText("once")).toBeInTheDocument();
  rerender(<StageChip stage="review-due" reviews={4} />);
  expect(screen.getByText("Review due")).toBeInTheDocument();
  rerender(<StageChip stage="solid" reviews={3} />);
  expect(screen.getByText("Solid")).toBeInTheDocument();
  rerender(<StageChip stage="solid" reviews={3} notForMe />);
  expect(screen.getByText("Not for me")).toBeInTheDocument();
});

it("puts a status chip on each unit of a topic page", async () => {
  mockFetch({
    "/api/topics/dsa.topic": { body: {
      id: "dsa.topic", name: "Some topic", domainId: "dsa", domainName: "DSA", parentId: null, parentName: null,
      subtopics: [],
      units: [
        { id: "dsa.b", title: "Title of dsa.b", type: "concept", difficulty: 2, estMinutes: 20 },
        { id: "db.hidden", title: "Title of db.hidden", type: "concept", difficulty: 2, estMinutes: 20 },
        { id: "dsa.new", title: "Not in the progress list", type: "concept", difficulty: 2, estMinutes: 20 },
      ],
    } },
    "/api/progress": { body: view },
    "/api/me/not-for-me": { body: { topics: [], units: ["db.hidden"] } },
  });
  render(
    <MemoryRouter initialEntries={["/topics/dsa.topic"]}>
      <Routes><Route path="/topics/:topicId" element={<TopicPage />} /></Routes>
    </MemoryRouter>,
  );
  const reviewed = await screen.findByText(/Reviewed/);
  const items = screen.getAllByRole("listitem");
  expect(items[0]).toContainElement(reviewed);
  expect(items[1]).toHaveTextContent("Title of db.hiddenNot for me");
  // A unit the progress list does not know gets no chip rather than a wrong one.
  expect(items[2]).toHaveTextContent(/^Not in the progress listConcept/);
});
