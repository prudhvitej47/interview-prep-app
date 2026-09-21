import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { DOMAINS, me, mockFetch } from "./testing";

describe("App", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("tells someone on the tailnet but not on the list why they cannot get in", async () => {
    mockFetch({ "/api/me": { status: 403 } });
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/not on its list of learners/i);
  });

  it("starts a new learner at onboarding", async () => {
    mockFetch({ "/api/me": { body: me() }, "/api/domains": { body: DOMAINS } });
    render(<App />);
    expect(await screen.findByText(/Where are you starting from/i)).toBeInTheDocument();
  });

  it("takes an onboarded learner straight home", async () => {
    mockFetch({
      "/api/me": { body: me({ onboarded: true }) },
      "/api/topics": { body: [{ id: "dsa.graphs" }, { id: "db.indexes" }] },
    });
    render(<App />);
    expect(await screen.findByText("Welcome, Tester.")).toBeInTheDocument();
    expect(screen.getByText("2 topics loaded.")).toBeInTheDocument();
  });

  it("shows the problem when the API is unreachable", async () => {
    mockFetch({ "/api/me": { status: 503 } });
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
  });
});
