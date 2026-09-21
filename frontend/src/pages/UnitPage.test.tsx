import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UnitPage } from "./UnitPage";
import { mockFetch, unit } from "../testing";

// jsdom cannot lay out SVG, so drawing is stubbed; mermaid-parser.test.ts covers the real parser.
vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn(async () => ({ svg: '<svg data-testid="drawn-diagram"></svg>' })),
  },
}));

const URL = "/api/units/ds.transactions.idempotency-keys";

function show(unitBody: object, note = { body: "", updatedAt: null }) {
  const fetchMock = mockFetch({ [URL]: { body: unitBody }, [`${URL}/note`]: { body: note } });
  render(
    <MemoryRouter initialEntries={["/units/ds.transactions.idempotency-keys"]}>
      <Routes>
        <Route path="/units/:unitId" element={<UnitPage />} />
      </Routes>
    </MemoryRouter>,
  );
  return fetchMock;
}

describe("UnitPage", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the unit, where it sits and what kind it is", async () => {
    show(unit());
    expect(await screen.findByRole("heading", { name: "Idempotency keys for safe retries" })).toBeInTheDocument();
    expect(screen.getByText(/Concept · difficulty 3\/5 · 30 min/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute("href", "/topics/ds.transactions");
    expect(screen.getByText("Retries can charge twice.")).toBeInTheDocument();
  });

  it("draws a mermaid block as a diagram, not as code", async () => {
    show(unit());
    const diagram = await screen.findByTestId("drawn-diagram");
    expect(screen.queryByText(/C->>S: pay/)).not.toBeInTheDocument();
    // Not left inside the code block's <pre>, which would frame it as code.
    expect(diagram.closest("pre")).toBeNull();
  });

  it("folds the solution of a coding problem until asked", async () => {
    show(unit({ type: "coding", markdown: "## Problem\nReverse a list.\n\n## Solution\nUse two pointers." }));
    const solution = await screen.findByText("Use two pointers.");
    expect(solution.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("Show solution")).toBeInTheDocument();
    expect(screen.getByText("Reverse a list.").closest("details")).toBeNull();
  });

  it("puts a coding problem's cases before its hints, and never shows the hidden ones", async () => {
    show(unit({
      type: "coding",
      markdown: "## Problem\nSum a window.\n\n## Hints\nKeep a running sum.\n\n## Solution\nSlide.",
      testCases: [
        { name: "the example", input: "nums = [2, 1, 5], k = 2", expected: "6" },
        { name: "every value negative", input: "nums = [-3, -1], k = 1", expected: "-1" },
      ],
      hiddenTestCases: 1,
    }));
    const practice = (await screen.findByRole("heading", { name: "Check your solution" })).closest("section")!;
    const hints = screen.getByText("Show hints");
    expect(practice.compareDocumentPosition(hints) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(practice).toHaveTextContent("1 more case is kept back");

    expect(within(practice).getByRole("status")).toHaveTextContent("0 of 2 pass");
    fireEvent.click(screen.getByLabelText("Passes: the example"));
    fireEvent.click(screen.getByLabelText("Passes: every value negative"));
    expect(within(practice).getByRole("status")).toHaveTextContent("Every case passes");
  });

  it("runs a SQL answer and says whether it is the answer", async () => {
    // sqlSession.test.ts runs real PGlite; here the page's side of it: lazy start, verdict, errors.
    const run = vi.fn()
      .mockResolvedValueOnce({ columns: ["id"], rows: [[2], [4]], verdict: { kind: "row-count", got: 2, want: 1 } })
      .mockRejectedValueOnce(new Error('column "nope" does not exist'));
    const openSession = vi.fn(async () => ({ run, close: vi.fn() }));
    vi.doMock("../practice/sqlSession", () => ({ openSession }));

    const fixture = { schema: "create table p (id int);", seed: "", reference: "select 1", orderMatters: false };
    show(unit({ type: "sql", markdown: "## Question\nWhich?\n\n## Solution\nThis.", sqlFixture: fixture }));
    const box = await screen.findByLabelText("Your query");
    // The attempt comes before the folded solution.
    expect(box.compareDocumentPosition(screen.getByText("Show solution")) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(openSession).not.toHaveBeenCalled();

    fireEvent.change(box, { target: { value: "select id from p" } });
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByText(/returns 2 rows, the answer has 1/)).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "4" })).toBeInTheDocument();
    expect(openSession).toHaveBeenCalledWith(fixture);

    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByRole("alert")).toHaveTextContent('column "nope" does not exist');
    expect(openSession).toHaveBeenCalledTimes(1);
    vi.doUnmock("../practice/sqlSession");
  });

  it("lists sources, linking the ones that have an address", async () => {
    show(unit());
    expect(await screen.findByText(/System Design Interview Vol. 2/)).toHaveTextContent("Ch. 11");
    expect(screen.getByRole("link", { name: "Stripe on idempotency" }))
      .toHaveAttribute("rel", expect.stringContaining("noopener"));
  });

  it("says when a unit has been retired", async () => {
    show(unit({ state: "retired" }));
    expect(await screen.findByRole("note")).toHaveTextContent(/retired/);
  });

  it("says so plainly when there is no such unit", async () => {
    mockFetch({ [URL]: { status: 404 } });
    render(
      <MemoryRouter initialEntries={["/units/ds.transactions.idempotency-keys"]}>
        <Routes>
          <Route path="/units/:unitId" element={<UnitPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("There is no such unit.");
  });

  it("saves a private note with the CSRF token", async () => {
    const fetchMock = show(unit());
    const box = await screen.findByLabelText("Your notes on this unit");
    await vi.waitFor(() => expect(box).toBeEnabled());

    fireEvent.change(box, { target: { value: "Keys live beside the payment row." } });
    expect(screen.getByRole("status")).toHaveTextContent("Unsaved changes");
    fetchMock.mockImplementationOnce(async () => ({
      ok: true, status: 200,
      json: async () => ({ body: "Keys live beside the payment row.", updatedAt: "2026-09-21T10:00:00Z" }),
    }));
    fireEvent.click(screen.getByRole("button", { name: "Save note" }));

    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/Saved/));
    const [, init] = fetchMock.mock.calls.at(-1)!;
    expect(init?.method).toBe("PUT");
    expect(init?.headers).toMatchObject({ "X-XSRF-TOKEN": "token-from-the-server" });
    expect(JSON.parse(init?.body as string)).toEqual({ body: "Keys live beside the payment row." });
  });
});
