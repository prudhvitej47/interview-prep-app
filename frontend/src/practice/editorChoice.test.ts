import { afterEach, describe, expect, it, vi } from "vitest";
import { storeChoice, storedChoice } from "./editorChoice";

describe("editor choice", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it("is plain until the learner picks highlighting, and remembers it", () => {
    expect(storedChoice()).toBe("plain");
    storeChoice("codemirror");
    expect(storedChoice()).toBe("codemirror");
    expect(localStorage.getItem("interview-prep.editor")).toBe("codemirror");
  });

  it("falls back to plain when storage is blocked, and storing does not throw", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    expect(storedChoice()).toBe("plain");
    expect(() => storeChoice("codemirror")).not.toThrow();
  });
});
