import { fireEvent, render, screen } from "@testing-library/react";
import { Link, MemoryRouter, Route, Routes, useNavigate } from "react-router";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useHeadingFocus, useScrollRestoration } from "./navigation";

function GoBack() {
  const navigate = useNavigate();
  return <button onClick={() => navigate(-1)}>Browser back</button>;
}

function Pages() {
  useScrollRestoration();
  useHeadingFocus();
  return (
    <main>
      <Routes>
        <Route path="/" element={<><h2>Home</h2><Link to="/second">Go on</Link></>} />
        <Route path="/second" element={<><h2>Second</h2><Link to="/">Back home</Link><GoBack /></>} />
      </Routes>
    </main>
  );
}

const show = () => render(<MemoryRouter initialEntries={["/"]}><Pages /></MemoryRouter>);

beforeEach(() => {
  vi.stubGlobal("scrollTo", vi.fn());
  sessionStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

it("stops the browser restoring scroll positions of its own", () => {
  show();
  expect(history.scrollRestoration).toBe("manual");
});

it("starts a page the reader opens at the top", () => {
  show();
  fireEvent.click(screen.getByRole("link", { name: "Go on" }));
  expect(window.scrollTo).toHaveBeenCalledWith(0, 0);
});

it("puts the keyboard on the new page's heading", () => {
  show();
  fireEvent.click(screen.getByRole("link", { name: "Go on" }));
  expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Second" }));
});

it("returns to where a page was left when the reader goes back", async () => {
  vi.useFakeTimers();
  try {
    show();
    Object.defineProperty(window, "scrollY", { value: 420, configurable: true });
    fireEvent.scroll(window);
    fireEvent.click(screen.getByRole("link", { name: "Go on" }));
    vi.mocked(window.scrollTo).mockClear();
    fireEvent.click(screen.getByRole("button", { name: "Browser back" }));
    vi.advanceTimersByTime(500);
    expect(window.scrollTo).toHaveBeenCalledWith(0, 420);
    expect(window.scrollTo).not.toHaveBeenCalledWith(0, 0);
  } finally {
    vi.useRealTimers();
  }
});

it("treats following a link as a new visit, not a return", () => {
  show();
  Object.defineProperty(window, "scrollY", { value: 420, configurable: true });
  fireEvent.scroll(window);
  fireEvent.click(screen.getByRole("link", { name: "Go on" }));
  vi.mocked(window.scrollTo).mockClear();
  fireEvent.click(screen.getByRole("link", { name: "Back home" }));
  expect(window.scrollTo).toHaveBeenCalledWith(0, 0);
});

// The focus above is deliberate and stays; only the ring it draws is in question. Browsers
// disagree about whether a heading focused by script after a click counts as "keyboard", so the
// app records which device the reader used and marks the heading when it was the mouse.
it("lands the heading without a ring when the reader clicked with the mouse", () => {
  show();
  const link = screen.getByRole("link", { name: "Go on" });
  fireEvent.pointerDown(link);
  fireEvent.click(link);
  const heading = screen.getByRole("heading", { name: "Second" });
  expect(document.activeElement).toBe(heading);
  expect(heading).toHaveAttribute("data-pointer-nav");
});

it("keeps the ring when the reader followed the link from the keyboard", () => {
  show();
  const link = screen.getByRole("link", { name: "Go on" });
  fireEvent.pointerDown(link);
  fireEvent.click(link);
  const second = screen.getByRole("link", { name: "Back home" });
  fireEvent.keyDown(second, { key: "Enter" });
  fireEvent.click(second);
  const heading = screen.getByRole("heading", { name: "Home" });
  expect(document.activeElement).toBe(heading);
  expect(heading).not.toHaveAttribute("data-pointer-nav");
});

it("drops the mark once the heading loses focus, so a later ring is never silenced", () => {
  show();
  const link = screen.getByRole("link", { name: "Go on" });
  fireEvent.pointerDown(link);
  fireEvent.click(link);
  const heading = screen.getByRole("heading", { name: "Second" });
  fireEvent.blur(heading);
  expect(heading).not.toHaveAttribute("data-pointer-nav");
});
