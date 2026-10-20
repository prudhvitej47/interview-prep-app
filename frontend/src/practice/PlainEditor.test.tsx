import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { PlainEditor } from "./PlainEditor";

function Harness({ initial }: { initial: string }) {
  const [value, setValue] = useState(initial);
  return <PlainEditor value={value} onChange={setValue} label="Your solution" />;
}

function editor() {
  return screen.getByLabelText("Your solution") as HTMLTextAreaElement;
}

describe("PlainEditor", () => {
  it("indents with Tab and keeps the indent on Enter", () => {
    render(<Harness initial={"    if (a) {"} />);
    const box = editor();
    box.setSelectionRange(12, 12);
    fireEvent.keyDown(box, { key: "Enter" });
    expect(box.value).toBe("    if (a) {\n        ");
    fireEvent.keyDown(box, { key: "Tab" });
    expect(box.value).toBe("    if (a) {\n            ");
  });

  it("keeps long lines on one line, as code editors do", () => {
    render(<Harness initial="x" />);
    expect(editor()).toHaveAttribute("wrap", "off");
  });

  it("never traps the keyboard: Shift+Tab and Esc then Tab are left to the browser", () => {
    render(<Harness initial="x" />);
    const box = editor();
    box.setSelectionRange(1, 1);
    expect(fireEvent.keyDown(box, { key: "Tab", shiftKey: true })).toBe(true); // not prevented
    fireEvent.keyDown(box, { key: "Escape" });
    expect(fireEvent.keyDown(box, { key: "Tab" })).toBe(true);
    expect(box.value).toBe("x");
    // Typing again re-arms Tab for indenting.
    fireEvent.keyDown(box, { key: "a" });
    expect(fireEvent.keyDown(box, { key: "Tab" })).toBe(false);
  });
});
