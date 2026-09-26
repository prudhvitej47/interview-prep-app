import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { NotForMe } from "./NotForMe";
import { mockFetch } from "../testing";

afterEach(() => vi.unstubAllGlobals());

it("offers to keep a unit out of the learner's plans, and sends that choice", async () => {
  const fetchMock = mockFetch({ "/api/me/not-for-me": { body: { topics: [], units: [] } } });
  render(<NotForMe scope="unit" id="dsa.window.p1" topicId="dsa.window" topicName="Sliding window" />);
  fireEvent.click(await screen.findByRole("button", { name: "Keep this unit out of my plans" }));
  const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
  expect(JSON.parse(String(put?.[1]?.body))).toEqual({ scope: "unit", id: "dsa.window.p1", excluded: true });
});

it("offers to put back a topic the learner kept out", async () => {
  mockFetch({ "/api/me/not-for-me": { body: { topics: ["dsa.window"], units: [] } } });
  render(<NotForMe scope="topic" id="dsa.window" />);
  expect(await screen.findByRole("button", { name: "Put it back" })).toBeInTheDocument();
  expect(screen.getByLabelText("Not for me")).toHaveTextContent("You have kept this topic out of your plans.");
});

it("says so on a unit whose whole topic is out, instead of offering a second switch", async () => {
  mockFetch({ "/api/me/not-for-me": { body: { topics: ["dsa.window"], units: [] } } });
  render(<NotForMe scope="unit" id="dsa.window.p1" topicId="dsa.window" topicName="Sliding window" />);
  expect(await screen.findByText(/Its topic, Sliding window, is out of your plans/)).toBeInTheDocument();
  expect(screen.queryByRole("button")).toBeNull();
});
