import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { DOMAINS, homeDomains, me, mockFetch, topics } from "./testing";

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );

describe("App", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("tells someone on the tailnet but not on the list why they cannot get in", async () => {
    mockFetch({ "/api/me": { status: 403 } });
    renderAt("/");
    expect(await screen.findByRole("alert")).toHaveTextContent(/not on its list of learners/i);
  });

  it("sends a new learner to onboarding, whatever address they opened", async () => {
    mockFetch({ "/api/me": { body: me() }, "/api/domains": { body: DOMAINS } });
    renderAt("/units/ds.transactions.idempotency-keys");
    expect(await screen.findByText(/Where are you starting from/i)).toBeInTheDocument();
  });

  it("lists reviews that are due above the curriculum", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
      "/api/reviews": { body: { due: [{ unitId: "ds.transactions.idempotency-keys", title: "Idempotency keys",
        type: "concept", estMinutes: 30, dueOn: "2026-09-20" }], nextDueOn: "2026-09-25" } },
    });
    renderAt("/");
    const review = await screen.findByRole("link", { name: "Idempotency keys" });
    expect(review).toHaveAttribute("href", "/units/ds.transactions.idempotency-keys");
    expect(review.closest("section")).toHaveTextContent("Reviews due 1");
  });

  it("shows no review section before anything has been done", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
      "/api/reviews": { body: { due: [], nextDueOn: null } },
    });
    renderAt("/");
    await screen.findByRole("link", { name: "Transactions" });
    expect(screen.queryByText(/Reviews due/)).not.toBeInTheDocument();
  });

  it("shows an onboarded learner the curriculum, opening areas that have something in them", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
    });
    renderAt("/");

    expect(await screen.findByText("Welcome, Tester.")).toBeInTheDocument();
    const transactions = await screen.findByRole("link", { name: "Transactions" });
    expect(transactions).toHaveAttribute("href", "/topics/ds.transactions");
    expect(transactions.closest("details")).toHaveAttribute("open");
    // An empty topic is shown, so the shape of the curriculum is visible, but not as a link.
    expect(screen.getByText("Payments").closest("a")).toBeNull();
    expect(screen.getByText("Payments").closest("details")).not.toHaveAttribute("open");
  });

  it("keeps the sections one click away from every page", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
    });
    renderAt("/");
    const sections = await screen.findByRole("navigation", { name: "Sections" });
    expect(within(sections).getByRole("link", { name: "This week" })).toHaveAttribute("href", "/week");
    expect(within(sections).getByRole("link", { name: "Interview evidence" })).toHaveAttribute("href", "/evidence");
    expect(within(sections).getByRole("link", { name: "How this works" })).toHaveAttribute("href", "/how-it-works");
    // The page you are on is marked, not just underlined on hover.
    expect(within(sections).getByRole("link", { name: "Home" })).toHaveClass("active");
  });

  it("shows the start guide on the home page until it is closed", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true, startGuideClosed: false }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
    });
    renderAt("/");
    expect(await screen.findByRole("region", { name: "Start here" })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "How this works" })[0]).toHaveAttribute("href", "/how-it-works");
  });

  it("leaves the start guide out once it has been closed", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true, startGuideClosed: true }) },
      "/api/domains": { body: homeDomains },
      "/api/topics": { body: topics },
    });
    renderAt("/");
    expect(await screen.findByText("Welcome, Tester.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Start here" })).toBeNull();
  });

  it("lets an onboarded learner change their ratings, starting from what they said before", async () => {
    const fetchMock = mockFetch({
      "/api/me": { body: me({ onboarded: true, domainRatings: { dsa: 2, databases: 4 } }) },
      "/api/domains": { body: DOMAINS },
      "/api/me/ratings/domains": { body: me({ onboarded: true, domainRatings: { dsa: 5, databases: 4 } }) },
    });
    renderAt("/ratings");

    expect(await screen.findByRole("heading", { name: "Change your ratings" })).toBeInTheDocument();
    const radios = await screen.findAllByRole("radio", { name: /Strong/ });
    expect(radios[1]).toBeChecked(); // databases was 4
    fireEvent.click(screen.getAllByRole("radio", { name: /Could teach it/ })[0]);
    fireEvent.click(screen.getByRole("button", { name: "Save and continue" }));

    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => url === "/api/me/ratings/domains")).toBe(true),
    );
  });

  // A unit or a topic is the curriculum, and the curriculum is the home page: without this the
  // section row had no pill at all on those pages and read as though it had shifted.
  it("keeps Home marked as the current section while reading a unit", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/units/ds.transactions.idempotency-keys": { status: 404 },
    });
    renderAt("/units/ds.transactions.idempotency-keys");
    const nav = await screen.findByRole("navigation", { name: "Sections" });
    expect(within(nav).getByRole("link", { name: "Home" })).toHaveClass("active");
    expect(within(nav).getByRole("link", { name: "This week" })).not.toHaveClass("active");
  });

  it("marks only the section the reader is actually in", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/evidence": { body: [] },
    });
    renderAt("/evidence");
    const nav = await screen.findByRole("navigation", { name: "Sections" });
    expect(within(nav).getByRole("link", { name: "Home" })).not.toHaveClass("active");
    expect(within(nav).getByRole("link", { name: "Interview evidence" })).toHaveClass("active");
  });

  it("shows the problem when the API is unreachable", async () => {
    mockFetch({ "/api/me": { status: 503 } });
    renderAt("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
  });
});
