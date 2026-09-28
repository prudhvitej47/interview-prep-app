import { useEffect } from "react";

/**
 * Asks before words that have not been saved are lost: closing the tab or reloading, and following
 * a link inside the app, which the browser does not count as leaving. Used by the note editor and
 * the answer box on a project page.
 */
export function useUnsavedGuard(dirty: boolean, question: string) {
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  // Every way off the page (nav pills, breadcrumbs, the plan strip) is a link. The check runs in the
  // capture phase, ahead of the router, and stops the click when the reader chooses to stay.
  useEffect(() => {
    if (!dirty) return;
    const ask = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const link = (e.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
      if (!link || link.target === "_blank" || link.origin !== window.location.origin) return;
      if (!window.confirm(question)) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    document.addEventListener("click", ask, true);
    return () => document.removeEventListener("click", ask, true);
  }, [dirty, question]);
}
