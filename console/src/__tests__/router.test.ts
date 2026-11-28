import test from "node:test";
import assert from "node:assert/strict";
import { parseRoute } from "../router.js";
import { installDom, navigateToHash } from "../test-support/dom.js";

test("parseRoute maps every known hash to its route", () => {
  assert.equal(parseRoute("#/relationships"), "relationships");
  assert.equal(parseRoute("#/links"), "links");
  assert.equal(parseRoute("#/labels"), "labels");
  assert.equal(parseRoute("#/data"), "data");
  assert.equal(parseRoute("#/drift"), "drift");
});

test("parseRoute falls back to relationships for empty and unknown hashes", () => {
  assert.equal(parseRoute(""), "relationships");
  assert.equal(parseRoute("#"), "relationships");
  assert.equal(parseRoute("#/"), "relationships");
  assert.equal(parseRoute("#/nope"), "relationships");
  assert.equal(parseRoute("#/pages/edit"), "relationships");
});

test("start renders the relationships placeholder and switches on hashchange", async () => {
  installDom();

  // Components are imported and registered only after the DOM exists, since
  // their classes extend HTMLElement at module-evaluation time.
  const { ComponentRegistry } = await import("@visual-ui/core");
  const { registerNativeComponents } = await import("@visual-ui/components");
  registerNativeComponents(new ComponentRegistry());

  // Build the same header/nav shape as index.html, with elements rather than
  // markup strings.
  const nav = document.createElement("nav");
  for (const route of ["relationships", "links", "labels", "data", "drift"]) {
    const link = document.createElement("a");
    link.href = `#/${route}`;
    link.setAttribute("data-route", route);
    link.textContent = route;
    nav.appendChild(link);
  }
  document.body.appendChild(nav);
  const app = document.createElement("main");
  app.id = "app";
  document.body.appendChild(app);

  const { start } = await import("../router.js");
  start(app);

  const initialCard = app.querySelector("ui-card");
  assert.ok(initialCard, "a ui-card is rendered into the container");
  assert.equal(initialCard.getAttribute("title"), "Relationships");
  // The card is an upgraded native component, so its shadow title is filled.
  assert.equal(
    initialCard.shadowRoot?.querySelector('[part="title"]')?.textContent,
    "Relationships",
  );
  assert.equal(
    nav.querySelector('a[data-route="relationships"]')?.getAttribute("aria-current"),
    "page",
  );
  assert.equal(
    nav.querySelector('a[data-route="drift"]')?.hasAttribute("aria-current"),
    false,
  );

  navigateToHash("#/drift");

  const driftCard = app.querySelector("ui-card");
  assert.equal(driftCard?.getAttribute("title"), "Drift");
  assert.equal(app.querySelectorAll("ui-card").length, 1, "screens replace, not stack");
  assert.equal(
    nav.querySelector('a[data-route="drift"]')?.getAttribute("aria-current"),
    "page",
  );
  assert.equal(
    nav.querySelector('a[data-route="relationships"]')?.hasAttribute("aria-current"),
    false,
  );
});
