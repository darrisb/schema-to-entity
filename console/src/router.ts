/**
 * Hash router for the console.
 *
 * parseRoute is pure string handling and testable without a DOM; start wires
 * hashchange events to the screen renderers below. The relationships, links,
 * labels, data and drift screens are real (CN-004, CN-005, CN-006, CN-007,
 * CN-008) and own their container through their start functions; a route with
 * no screen yet falls through to the placeholder card at the end.
 */

import { createClient, type ApiClient } from "./api/client.js";
import { startData } from "./data/screen.js";
import { startDrift } from "./drift/screen.js";
import { startLabels } from "./labels/screen.js";
import { startLinks } from "./links/screen.js";
import { startRelationships } from "./relationships/screen.js";

export type Route = "relationships" | "links" | "labels" | "data" | "drift";

export const ROUTES: readonly Route[] = [
  "relationships",
  "links",
  "labels",
  "data",
  "drift",
];

const DEFAULT_ROUTE: Route = "relationships";

const SCREEN_TITLES: Record<Route, string> = {
  relationships: "Relationships",
  links: "Links",
  labels: "Labels",
  data: "Data",
  drift: "Drift",
};

const SCREEN_DESCRIPTIONS: Record<Route, string> = {
  relationships: "Review and confirm proposed entity relationships.",
  links: "Manage manual links between entities.",
  labels: "Edit entity and field labels.",
  data: "Browse the read-only data API.",
  drift: "Inspect schema drift against baselines.",
};

function isRoute(value: string): value is Route {
  return (ROUTES as readonly string[]).includes(value);
}

/** Maps a location hash ("#/links", "", "#/unknown", …) onto a Route. */
export function parseRoute(hash: string): Route {
  const name = hash.replace(/^#\/?/, "").trim();
  return isRoute(name) ? name : DEFAULT_ROUTE;
}

/** Replaces the container contents with the screen for one route. */
export function renderScreen(
  container: HTMLElement,
  route: Route,
  client: ApiClient = createClient(),
): void {
  if (route === "relationships") {
    // The screen fetches on its own; the returned settle function is for
    // tests, and screen failures are reported in the screen's error area.
    void startRelationships(container, client);
    return;
  }
  if (route === "links") {
    // The links screen (CN-005) fetches, validates and renders on its own,
    // exactly like the relationships screen above.
    void startLinks(container, client);
    return;
  }
  if (route === "labels") {
    // The labels screen (CN-006) fetches, renders the picked entity's rows
    // and reloads after every edit, on its own like the two above.
    void startLabels(container, client);
    return;
  }
  if (route === "data") {
    // The data screen (CN-007) fetches the document, then pages and sorts
    // one entity's rows through the table's own events, on its own again.
    void startData(container, client);
    return;
  }
  if (route === "drift") {
    // The drift screen (CN-008) reads every source's report, groups its
    // changes by severity and runs the accept/rename actions on its own.
    void startDrift(container, client);
    return;
  }
  const card = document.createElement("ui-card");
  card.setAttribute("title", SCREEN_TITLES[route]);
  card.setAttribute("description", SCREEN_DESCRIPTIONS[route]);
  container.replaceChildren(card);
}

/** Marks the nav link for the active route with aria-current="page". */
function markActiveNavLink(route: Route): void {
  const links = document.querySelectorAll("nav a[data-route]");
  for (const link of links) {
    if (link.getAttribute("data-route") === route) {
      link.setAttribute("aria-current", "page");
    } else {
      link.removeAttribute("aria-current");
    }
  }
}

/**
 * Renders the current screen into the container and re-renders on every
 * hashchange, updating the active nav link. Returns a function that stops
 * listening.
 */
export function start(container: HTMLElement): () => void {
  const render = (): void => {
    const route = parseRoute(window.location.hash);
    renderScreen(container, route);
    markActiveNavLink(route);
  };
  window.addEventListener("hashchange", render);
  render();
  return (): void => window.removeEventListener("hashchange", render);
}
