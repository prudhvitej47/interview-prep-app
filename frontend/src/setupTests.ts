import "@testing-library/jest-dom/vitest";

// Node 25 ships its own localStorage global, which without --localstorage-file is a stub that shadows
// jsdom's. Tests get jsdom's real Storage, as a browser would.
declare const jsdom: { window: Window } | undefined;
if (typeof jsdom !== "undefined") {
  Object.defineProperty(globalThis, "localStorage", { value: jsdom.window.localStorage, configurable: true });
}
