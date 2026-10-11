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
