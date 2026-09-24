import { useEffect } from "react";
import { useLocation, useNavigationType } from "react-router";

const key = (k: string) => `scroll:${k}`;

function scrollTo(y: number) {
  // jsdom has no layout and throws here; a failed scroll must never break a page.
  try {
    window.scrollTo(0, y);
  } catch {
    /* ignore */
  }
}

function remember(k: string, y: number) {
  try {
    sessionStorage.setItem(key(k), String(y));
  } catch {
    /* private browsing, or storage full */
  }
}

function remembered(k: string): number {
  try {
    return Number(sessionStorage.getItem(key(k)) ?? 0);
  } catch {
    return 0;
  }
}

/**
 * What a browser does for itself on a normal site, and a single-page app has to do for itself:
 * a new page starts at the top, and Back or Forward returns to where that page was left.
 *
 * <p>Pages here load their data after they render, so the content that was scrolled past may not
 * exist yet when a Back lands. The position is therefore reapplied a couple of times over the next
 * half second, and dropped as soon as the reader scrolls themselves.
 */
export function useScrollRestoration() {
  const location = useLocation();
  const navigationType = useNavigationType();

  useEffect(() => {
    // The browser restores a position it recorded for a page this app has since rebuilt, and it
    // does so before the data is back, landing anywhere. Ours below is the one that waits.
    try {
      history.scrollRestoration = "manual";
    } catch {
      /* not supported here */
    }
  }, []);

  useEffect(() => {
    if (navigationType !== "POP") {
      scrollTo(0);
      return;
    }
    const y = remembered(location.key);
    if (y === 0) return;
    // The page cannot be scrolled to where it was until the data that made it that tall is back,
    // so keep trying each frame until it lands, the reader takes over, or a second and a half.
    // It has to hold for a few frames: late-arriving content can nudge the page after it lands.
    let held = 0;
    return repeatUntil(() => {
      scrollTo(y);
      held = Math.abs(window.scrollY - y) <= 2 ? held + 1 : 0;
      return held >= 3;
    });
  }, [location.key, navigationType]);

  useEffect(() => {
    const k = location.key;
    const save = () => remember(k, window.scrollY);
    window.addEventListener("scroll", save, { passive: true });
    return () => {
      window.removeEventListener("scroll", save);
    };
  }, [location.key]);
}

/**
 * Which device the reader last reached for. A heading focused by script wears the focus ring or
 * not according to each browser's own guess about that, and the guesses disagree: Chrome reads a
 * script focus after a click as "mouse" and stays quiet, WebKit reads it as "keyboard" and draws
 * the ring. Recording it ourselves makes the answer the same everywhere.
 */
let lastInputWasPointer = false;
if (typeof window !== "undefined") {
  window.addEventListener("pointerdown", () => (lastInputWasPointer = true), true);
  window.addEventListener("mousedown", () => (lastInputWasPointer = true), true);
  window.addEventListener("keydown", () => (lastInputWasPointer = false), true);
}

/**
 * Moves the keyboard's place to the new page's heading. Without it, Tab after following a link
 * starts again from the top of the window, and a screen reader says nothing about where it landed.
 * The heading arrives with the page's data, so this waits for it the same way.
 *
 * <p>The focus always happens; only the ring is conditional. A reader who clicked with the mouse
 * gets the heading marked `data-pointer-nav`, which index.css uses to hold the ring back for that
 * one element; a reader who pressed Enter on the link gets the ring as usual. The mark is dropped
 * when the heading loses focus, so it can never silence a later keyboard focus.
 */
export function useHeadingFocus() {
  const location = useLocation();
  const navigationType = useNavigationType();

  useEffect(() => {
    if (navigationType === "POP") return;
    return repeatUntil(() => {
      const heading = document.querySelector<HTMLElement>("main h2");
      if (!heading) return false;
      heading.tabIndex = -1;
      if (lastInputWasPointer) {
        heading.setAttribute("data-pointer-nav", "");
        heading.addEventListener("blur", () => heading.removeAttribute("data-pointer-nav"), { once: true });
      } else {
        heading.removeAttribute("data-pointer-nav");
      }
      heading.focus({ preventScroll: true });
      return true;
    });
  }, [location.key, navigationType]);
}

const FRAME = 16;
const GIVE_UP_AFTER = 1500;

/**
 * Runs `attempt` every frame until it reports success, the reader scrolls or touches the page, or
 * a second and a half has passed. Returns the cleanup an effect needs.
 */
function repeatUntil(attempt: () => boolean): () => void {
  let stopped = false;
  let handle: ReturnType<typeof setTimeout> | number = 0;
  const stop = () => (stopped = true);
  window.addEventListener("wheel", stop, { passive: true });
  window.addEventListener("touchstart", stop, { passive: true });
  window.addEventListener("keydown", stop);
  const started = Date.now();

  const tick = () => {
    if (stopped || attempt() || Date.now() - started > GIVE_UP_AFTER) return;
    handle = typeof requestAnimationFrame === "function"
      ? requestAnimationFrame(tick)
      : setTimeout(tick, FRAME);
  };
  tick();

  return () => {
    stopped = true;
    if (typeof cancelAnimationFrame === "function" && typeof handle === "number") cancelAnimationFrame(handle);
    clearTimeout(handle as ReturnType<typeof setTimeout>);
    window.removeEventListener("wheel", stop);
    window.removeEventListener("touchstart", stop);
    window.removeEventListener("keydown", stop);
  };
}
