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
