import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { HowItWorks } from "./HowItWorks";
import { me, mockFetch } from "../testing";

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=token-from-the-server";
});
afterEach(() => vi.unstubAllGlobals());

it("explains the plan, reviews and rewards", () => {
  render(<MemoryRouter><HowItWorks me={me({ onboarded: true })} onMe={vi.fn()} /></MemoryRouter>);
  expect(screen.getByRole("heading", { name: "How this works" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "1. Your week" })).toBeInTheDocument();
  expect(screen.getByText(/The week's/)).toHaveTextContent("80% of the planned minutes");
  expect(screen.getByRole("heading", { name: "4. Marking it done, and reviews" })).toBeInTheDocument();
});

it("brings a closed start guide back", async () => {
  const fetchMock = mockFetch({ "/api/me/start-guide": { body: me({ onboarded: true, startGuideClosed: false }) } });
  const onMe = vi.fn();
  render(<MemoryRouter><HowItWorks me={me({ onboarded: true, startGuideClosed: true })} onMe={onMe} /></MemoryRouter>);
  fireEvent.click(screen.getByRole("button", { name: "Show it again" }));
  await waitFor(() => expect(onMe).toHaveBeenCalledWith(expect.objectContaining({ startGuideClosed: false })));
  expect(fetchMock.mock.calls[0][1]).toMatchObject({ body: JSON.stringify({ closed: false }) });
});
