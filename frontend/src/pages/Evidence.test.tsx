import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EvidencePage } from "./EvidencePage";
import { EvidenceDetailPage } from "./EvidenceDetailPage";
import { DraftPage } from "./DraftPage";
import { mockFetch } from "../testing";

const report = (id: string, companyId: string, company: string) => ({
  id, companyId, company, role: null, level: "senior", location: "India", interviewDate: "2025-07",
  sourceKind: "candidate-report", sourceTitle: "A report", publisher: "Taro", tier: "secondary", outcome: null,
  rounds: 3, questions: 2, topics: ["ds.transactions"],
});

function at(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/evidence" element={<EvidencePage />} />
        <Route path="/evidence/drafts/:draftId" element={<DraftPage />} />
        <Route path="/evidence/:evidenceId" element={<EvidenceDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("evidence", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("lists the reports and filters them by company", async () => {
    mockFetch({
      "/api/evidence": { body: [report("ev-a", "stripe", "Stripe"), report("ev-b", "wise", "Wise")] },
      "/api/evidence/drafts": { body: [] },
    });
    at("/evidence");
    expect(await screen.findByRole("link", { name: /Stripe · senior · India/ })).toHaveAttribute("href", "/evidence/ev-a");
    expect(screen.getAllByText(/2025-07 · Candidate report \(Taro\) · 3 rounds, 2 questions/)).toHaveLength(2);
    fireEvent.change(screen.getByLabelText("Company"), { target: { value: "wise" } });
    expect(screen.queryByRole("link", { name: /Stripe/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Wise/ })).toBeInTheDocument();
  });

  it("shows a report round by round, with each question's topics linked", async () => {
    mockFetch({ "/api/evidence/ev-a": { body: {
      summary: report("ev-a", "stripe", "Stripe"), sourceUrl: "https://example.com/r", accessed: "2026-09-18", notes: null,
      rounds: [
        { type: "coding", name: "Coding", summary: "Phone screen", questions: [] },
        { type: "hld", name: "High-level design", summary: null,
          questions: [{ text: "Design idempotent retries", topics: [{ id: "ds.transactions", name: "Transactions" }] }] },
      ] } } });
    at("/evidence/ev-a");
    expect(await screen.findByRole("link", { name: "A report" })).toHaveAttribute("href", "https://example.com/r");
    expect(screen.getByText("High-level design")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute("href", "/topics/ds.transactions");
  });

  it("saves a debrief, lists what stops it exporting, then exports it", async () => {
    const fetchMock = mockFetch({
      "/api/evidence/options": { body: { companies: [{ id: "stripe", name: "Stripe" }], rounds: [{ id: "hld", name: "High-level design" }] } },
      "/api/topics": { body: [{ id: "ds.transactions", domainId: "distributed", parentId: null, name: "Transactions", unitCount: 1 }] },
      "/api/evidence/drafts": { body: { id: 7, kind: "debrief", body: {}, createdAt: "", updatedAt: "" } },
      "/api/evidence/drafts/7": { body: { id: 7, kind: "debrief", body: {}, createdAt: "", updatedAt: "" } },
      "/api/evidence/drafts/7/export": { status: 422, body: { problems: ["Choose the company."] } },
    });
    at("/evidence/drafts/new?kind=debrief");
    expect(await screen.findByRole("heading", { name: "Log an interview debrief" })).toBeInTheDocument();
    await screen.findByRole("option", { name: "High-level design" });

    fireEvent.change(screen.getByLabelText("Round 1 type"), { target: { value: "hld" } });
    fireEvent.click(screen.getByRole("button", { name: "Add a question" }));
    fireEvent.change(screen.getByLabelText("Question 1"), { target: { value: "Design idempotent retries" } });
    fireEvent.change(screen.getByLabelText("Question 1 topic"), { target: { value: "ds.transactions" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(screen.getByRole("button", { name: "Remove Transactions" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Private notes (never exported)"), { target: { value: "Felt rushed" } });

    fireEvent.click(screen.getByRole("button", { name: "Export as an evidence file" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose the company.");
    const post = fetchMock.mock.calls.find(([u, i]) => u === "/api/evidence/drafts" && i?.method === "POST")!;
    expect(JSON.parse(post[1]!.body as string)).toEqual({ kind: "debrief", body: {
      rounds: [{ type: "hld", questions: [{ text: "Design idempotent retries", topics: ["ds.transactions"] }] }],
      private_notes: "Felt rushed" } });

    // The first save moved the address to /evidence/drafts/7 without reloading the form.
    expect(screen.getByLabelText("Question 1")).toHaveValue("Design idempotent retries");
    expect(fetchMock.mock.calls.some(([u, i]) => u === "/api/evidence/drafts/7" && !i?.method)).toBe(false);

    fetchMock.mockImplementation(async (url: string) => ({
      ok: true, status: 200,
      json: async () => url.endsWith("/export")
        ? { path: "evidence/2026/ev-2026-10-stripe-debrief.yaml", yaml: "id: ev-2026-10-stripe-debrief\n" }
        : { id: 7, kind: "debrief", body: {}, createdAt: "", updatedAt: "" },
    }));
    fireEvent.click(screen.getByRole("button", { name: "Export as an evidence file" }));
    const file = await screen.findByRole("region", { name: "Evidence file" });
    expect(within(file).getByText("evidence/2026/ev-2026-10-stripe-debrief.yaml")).toBeInTheDocument();
    expect(file).toHaveTextContent("id: ev-2026-10-stripe-debrief");
  });
});
