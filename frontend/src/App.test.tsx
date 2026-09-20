import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

describe("App", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("says so when the curriculum is empty", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: true, json: async () => [] }),
    );

    render(<App />);

    expect(await screen.findByText(/No curriculum loaded yet/i)).toBeInTheDocument();
  });

  it("counts the topics it loaded", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          { id: "dsa.sliding-window", domainId: "dsa", parentId: null, name: "Sliding window" },
          { id: "db.indexes", domainId: "databases", parentId: null, name: "Indexes" },
        ],
      }),
    );

    render(<App />);

    expect(await screen.findByText("2 topics loaded.")).toBeInTheDocument();
  });

  it("shows the problem when the API is unreachable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 503 }));

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent("503");
  });
});
