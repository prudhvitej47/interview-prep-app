import { fireEvent, render, screen } from "@testing-library/react";
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

  it("shows the problem when the API is unreachable", async () => {
    mockFetch({ "/api/me": { status: 503 } });
    renderAt("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
  });
});
