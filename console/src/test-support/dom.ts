import { Window } from "happy-dom";

let installed = false;

/**
 * Creates a happy-dom Window and copies the browser globals the UI library
 * relies on onto globalThis, so the vendored components (which extend
 * HTMLElement and call customElements.define at import time) load under
 * plain node --test.
 *
 * DOM tests must call this before importing @visual-ui packages, then
 * register the components once (registerNativeComponents is safe to call
 * repeatedly, but the Window here is installed once per test process).
 */
export function installDom(url = "http://localhost:5174/console/"): void {
  if (installed) {
    return;
  }
  const window = new Window({ url });
  const globals: Record<string, unknown> = {
    window,
    document: window.document,
    customElements: window.customElements,
    HTMLElement: window.HTMLElement,
    Event: window.Event,
    CustomEvent: window.CustomEvent,
    location: window.location,
  };
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, name, {
      value,
      writable: true,
      configurable: true,
    });
  }
  installed = true;
}

/** Simulates navigating to a hash: updates location and fires hashchange. */
export function navigateToHash(hash: string): void {
  window.location.hash = hash;
  // global Event is happy-dom's Event once installDom() has run, which is
  // required for the window to accept the dispatch.
  window.dispatchEvent(new Event("hashchange"));
}
