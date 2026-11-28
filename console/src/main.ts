import { ComponentRegistry } from "@visual-ui/core";
import { registerNativeComponents } from "@visual-ui/components";
import { start } from "./router.js";

// Define ui-card, ui-text, ui-button, ui-dropdown, ui-input and ui-table
// before the first render so the placeholder screens are upgraded.
registerNativeComponents(new ComponentRegistry());

const app = document.getElementById("app");
if (app) {
  start(app);
}
