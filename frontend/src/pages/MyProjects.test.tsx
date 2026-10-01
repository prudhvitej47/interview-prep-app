import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { importSummary, MyProjects } from "./MyProjects";
import { mockFetch } from "../testing";

// Everything here is invented: this repository is public.
const PROJECTS = [
  { id: 1, name: "Example ledger", questions: 6, answered: 2, due: 1 },
  { id: 2, name: "Example gateway", questions: 1, answered: 0, due: 0 },
];

const IMPORTED = {
  projectsAdded: 1,
  projectsUpdated: 0,
  projectsRetired: 0,
  questionsAdded: 3,
  questionsUpdated: 2,
  questionsRetired: 1,
  unknownUnits: ["no.such.unit"],
};

function show() {
  render(
    <MemoryRouter>
      <MyProjects />
    </MemoryRouter>,
  );
}

function choose(text: string) {
  const file = new File([text], "questions.json", { type: "application/json" });
  // jsdom's File has no text(); every browser the app runs in does.
  Object.defineProperty(file, "text", { value: async () => text });
  fireEvent.change(screen.getByLabelText("Question file"), { target: { files: [file] } });
}

describe("MyProjects", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("lists the projects in order with what is answered and due", async () => {
    mockFetch({ "/api/projects": { body: PROJECTS }, "/api/projects/hidden": { body: [] } });
    show();
    expect(await screen.findByRole("link", { name: "Example ledger" })).toHaveAttribute("href", "/projects/1");
    expect(screen.getByText("6 questions · 2 answered · 1 due")).toBeInTheDocument();
    // Nothing due says nothing about it.
    expect(screen.getByText("1 question · 0 answered")).toBeInTheDocument();
  });

  it("explains where to start when there is nothing yet", async () => {
    mockFetch({ "/api/projects": { body: [] }, "/api/projects/hidden": { body: [] } });
    show();
    expect(await screen.findByText(/No projects yet/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Export" })).toBeDisabled();
  });

  it("sends the chosen file as it is and says what the import changed", async () => {
    const fetchMock = mockFetch({
      "/api/projects": { body: PROJECTS }, "/api/projects/hidden": { body: [] },
      "/api/projects/import": { body: IMPORTED },
    });
    show();
    await screen.findByRole("link", { name: "Example ledger" });
    choose('{"version": 1, "projects": []}');

    expect(await screen.findByText("Imported: 1 project added; 3 questions added, 2 updated, 1 retired."))
      .toBeInTheDocument();
    expect(screen.getByText(/their answers are kept/)).toBeInTheDocument();
    expect(screen.getByText("Not in the curriculum, so left out of the links: no.such.unit.")).toBeInTheDocument();
    const sent = fetchMock.mock.calls.find(([url]) => url === "/api/projects/import")!;
    expect(sent[1]).toMatchObject({ method: "POST", body: '{"version": 1, "projects": []}' });
    expect((sent[1]!.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("token-from-the-server");
    // The list is fetched again, so the new counts show.
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url === "/api/projects")).toHaveLength(2));
  });

  it("lists every problem when a file is refused", async () => {
    mockFetch({
      "/api/projects": { body: PROJECTS }, "/api/projects/hidden": { body: [] },
      "/api/projects/import": { status: 422, body: { problems: ['"version" must be 1.', "Project 1: \"key\" is missing."] } },
    });
    show();
    await screen.findByRole("link", { name: "Example ledger" });
    choose("{}");
    expect(await screen.findByRole("alert")).toHaveTextContent("Nothing was imported. Fix these and try again:");
    expect(screen.getByText('"version" must be 1.')).toBeInTheDocument();
    expect(screen.getByText('Project 1: "key" is missing.')).toBeInTheDocument();
  });

  it("refuses a file over 1 MB without sending it", async () => {
    const fetchMock = mockFetch({ "/api/projects": { body: PROJECTS }, "/api/projects/hidden": { body: [] } });
    show();
    await screen.findByRole("link", { name: "Example ledger" });
    choose("x".repeat(1024 * 1024 + 1));
    expect(await screen.findByRole("alert")).toHaveTextContent("The file is larger than 1 MB.");
    expect(fetchMock.mock.calls.map(([url]) => url)).not.toContain("/api/projects/import");
  });

  it("words an unchanged import plainly", () => {
    expect(importSummary({ ...IMPORTED, projectsAdded: 0, questionsAdded: 0, questionsUpdated: 0, questionsRetired: 0 }))
      .toBe("Imported. Nothing had changed since the last import.");
    expect(importSummary({ ...IMPORTED, projectsAdded: 0, projectsUpdated: 1, questionsAdded: 0, questionsUpdated: 0, questionsRetired: 0 }))
      .toBe("Imported: 1 project updated.");
  });

  it("adds a project without a file", async () => {
    const fetchMock = mockFetch({
      "/api/projects": { body: PROJECTS },
      "/api/projects/hidden": { body: [] },
    });
    show();
    fireEvent.click(await screen.findByRole("button", { name: "New project" }));
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "Example gateway" } });
    fireEvent.click(screen.getByRole("button", { name: "Add project" }));
    await waitFor(() => {
      const posted = fetchMock.mock.calls.filter(([u, init]) => u === "/api/projects" && init?.method === "POST");
      expect(JSON.parse(posted[0][1]!.body as string)).toEqual({ name: "Example gateway", summary: "" });
    });
  });

  it("lists hidden projects so they can be restored", async () => {
    const fetchMock = mockFetch({
      "/api/projects": { body: PROJECTS },
      "/api/projects/hidden": { body: [{ id: 9, name: "An old project" }] },
      "/api/projects/9/restore": { status: 204 },
    });
    show();
    fireEvent.click(await screen.findByText("Hidden projects (1)"));
    fireEvent.click(screen.getByRole("button", { name: "Restore" }));
    await waitFor(() => expect(fetchMock.mock.calls.map(([u]) => u)).toContain("/api/projects/9/restore"));
  });
});
