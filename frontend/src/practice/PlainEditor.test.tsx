import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlainEditor } from "./PlainEditor";

function Harness({ initial }: { initial: string }) {
  const [value, setValue] = useState(initial);
  return <PlainEditor value={value} onChange={setValue} label="Your solution" />;
}

function editor() {
  return screen.getByLabelText("Your solution") as HTMLTextAreaElement;
}

describe("PlainEditor", () => {
  afterEach(() => {
    delete (document as { execCommand?: unknown }).execCommand;
  });

  it("indents with Tab and keeps the indent on Enter", () => {
    render(<Harness initial={"    if (a) {"} />);
    const box = editor();
    box.setSelectionRange(12, 12);
    fireEvent.keyDown(box, { key: "Enter" });
    expect(box.value).toBe("    if (a) {\n        ");
    fireEvent.keyDown(box, { key: "Tab" });
    expect(box.value).toBe("    if (a) {\n            ");
  });

  it("types its indents through the browser's own edit, so Cmd+Z can undo them", () => {
    // jsdom has no editing commands; a browser inserts the text and fires input itself.
    const exec = vi.fn((_cmd: string, _ui?: boolean, text?: string) => {
      const box = editor();
      const start = box.selectionStart;
      box.setRangeText(text ?? "", start, box.selectionEnd, "end");
      box.dispatchEvent(new Event("input", { bubbles: true }));
      return true;
    });
    Object.defineProperty(document, "execCommand", { value: exec, configurable: true });
    render(<Harness initial={"    if (a) {"} />);
    const box = editor();
    box.setSelectionRange(12, 12);
    fireEvent.keyDown(box, { key: "Enter" });
    expect(exec).toHaveBeenCalledWith("insertText", false, "\n        ");
    expect(box.value).toBe("    if (a) {\n        ");
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
