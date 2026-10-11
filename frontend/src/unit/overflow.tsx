import { useLayoutEffect, useState, type RefObject } from "react";

/** Whether an element is wider inside than out, rechecked when its size changes. */
export function useOverflow(box: RefObject<HTMLElement | null>, recheck?: unknown): boolean {
  const [overflows, setOverflows] = useState(false);
  useLayoutEffect(() => {
    const element = box.current;
    if (!element) return;
    const check = () => setOverflows(element.scrollWidth > element.clientWidth + 1);
    check();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(check);
    observer.observe(element);
    return () => observer.disconnect();
  }, [box, recheck]);
  return overflows;
}

/**
 * Said under a diagram that is wider than its frame. The frame's own name already tells a screen
 * reader (", scrolls sideways"), so this line is for the eye only: on a phone nothing else shows
 * there is more of the picture to the right.
 */
export function ScrollsNote() {
  return <p className="hint scrolls-note" aria-hidden="true">Scrolls sideways →</p>;
}
