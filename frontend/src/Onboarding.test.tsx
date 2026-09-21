import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Onboarding } from "./Onboarding";
import { DOMAINS, me, mockFetch } from "./testing";

describe("Onboarding", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=token-from-the-server";
  });
  afterEach(() => vi.unstubAllGlobals());

  it("cannot be saved until every area is rated", async () => {
    mockFetch({ "/api/domains": { body: DOMAINS } });
    render(<Onboarding me={me()} onDone={() => {}} />);

    const button = await screen.findByRole("button", { name: "2 left to rate" });
    expect(button).toBeDisabled();

    fireEvent.click(screen.getAllByRole("radio", { name: /Comfortable/ })[0]);
    expect(screen.getByRole("button", { name: "1 left to rate" })).toBeDisabled();

    fireEvent.click(screen.getAllByRole("radio", { name: /Strong/ })[1]);
    expect(screen.getByRole("button", { name: "Save and continue" })).toBeEnabled();
  });

  it("saves the ratings with the CSRF token the server issued", async () => {
    const fetchMock = mockFetch({
      "/api/domains": { body: DOMAINS },
      "/api/me/ratings/domains": { body: me({ onboarded: true }) },
    });
    const onDone = vi.fn();
    render(<Onboarding me={me()} onDone={onDone} />);

    fireEvent.click((await screen.findAllByRole("radio", { name: /Comfortable/ }))[0]);
    fireEvent.click(screen.getAllByRole("radio", { name: /Could teach it/ })[1]);
    fireEvent.click(screen.getByRole("button", { name: "Save and continue" }));

    await vi.waitFor(() => expect(onDone).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls.find(([u]) => u === "/api/me/ratings/domains")!;
    expect(url).toBe("/api/me/ratings/domains");
    expect(init?.method).toBe("PUT");
    // Without this header the server refuses the change; see SecurityConfig.
    expect(init?.headers).toMatchObject({ "X-XSRF-TOKEN": "token-from-the-server" });
    expect(JSON.parse(init?.body as string)).toEqual({
      ratings: { dsa: 3, databases: 5 },
    });
  });

  it("explains when there is no curriculum to rate yet", async () => {
    mockFetch({ "/api/domains": { body: [] } });
    render(<Onboarding me={me()} onDone={() => {}} />);
    expect(await screen.findByText(/No curriculum has been loaded yet/)).toBeInTheDocument();
  });
});
