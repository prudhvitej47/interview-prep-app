import { render, waitFor } from "@testing-library/react";
import { EditorView } from "@codemirror/view";
import { describe, expect, it, vi } from "vitest";
import CodeMirrorEditor from "./CodeMirrorEditor";

describe("CodeMirrorEditor", () => {
  it("shows the code, reports edits, and takes new code from outside", async () => {
    const onChange = vi.fn();
    const { container, rerender } = render(<CodeMirrorEditor value="class Solution {}" onChange={onChange} label="Your solution" />);
    const content = container.querySelector(".cm-content") as HTMLElement;
    expect(content).toHaveAttribute("aria-label", "Your solution");
    const view = EditorView.findFromDOM(container.querySelector(".cm-editor") as HTMLElement)!;
    expect(view.state.doc.toString()).toBe("class Solution {}");

    view.dispatch({ changes: { from: 0, insert: "// x\n" } });
    expect(onChange).toHaveBeenLastCalledWith("// x\nclass Solution {}");

    rerender(<CodeMirrorEditor value="int y;" onChange={onChange} label="Your solution" />);
    await waitFor(() => expect(view.state.doc.toString()).toBe("int y;"));
    expect(onChange).toHaveBeenCalledTimes(1); // outside changes are not echoed back
  });

  it("is read-only while disabled", () => {
    const { container } = render(<CodeMirrorEditor value="x" onChange={() => {}} label="Your solution" disabled />);
    expect(container.querySelector(".cm-content")).toHaveAttribute("contenteditable", "false");
  });
});
