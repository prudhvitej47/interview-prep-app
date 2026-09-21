import { fireEvent, render, screen } from "@testing-library/react";
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
