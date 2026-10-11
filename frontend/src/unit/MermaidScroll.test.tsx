import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Mermaid } from "./Mermaid";

// jsdom has no layout and cannot run Mermaid's renderer: hand back a drawn diagram, and say how
// wide its frame measures inside.
vi.mock("mermaid", () => ({
  default: { initialize: () => undefined, render: async () => ({ svg: '<svg viewBox="0 0 900 200"></svg>' }) },
}));

const scrollWidth = Object.getOwnPropertyDescriptor(Element.prototype, "scrollWidth")!;
const frameInside = (width: number) => Object.defineProperty(Element.prototype, "scrollWidth", {
  configurable: true,
  get() { return (this as Element).classList.contains("diagram") ? width : 0; },
});
afterEach(() => Object.defineProperty(Element.prototype, "scrollWidth", scrollWidth));

it("keeps room for the diagram while it draws, so the page does not jump", () => {
  frameInside(0);
  const { container } = render(<Mermaid chart={"flowchart LR\n  a --> b"} />);
  expect(container.querySelector(".diagram")).toHaveClass("pending");
});

it("says under a diagram wider than its frame that it scrolls sideways", async () => {
  frameInside(900);
  render(<Mermaid chart={"flowchart LR\n  a --> b"} />);
  expect(await screen.findByRole("figure", { name: "Diagram, scrolls sideways" })).not.toHaveClass("pending");
  expect(screen.getByText("Scrolls sideways →")).toHaveClass("hint");
});

it("says nothing under a diagram that fits", async () => {
  frameInside(0);
  render(<Mermaid chart={"flowchart LR\n  a --> b"} />);
  expect(await screen.findByRole("figure", { name: "Diagram" })).toBeInTheDocument();
  expect(screen.queryByText("Scrolls sideways →")).toBeNull();
});
