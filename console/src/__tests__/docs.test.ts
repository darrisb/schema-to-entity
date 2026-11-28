import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

/**
 * CN-011: keep the console READMEs synchronized with what actually runs.
 *
 * The docs describe commands, the test pipeline and dev-server URLs that live
 * in package.json and vite.config.ts. These assertions re-derive each
 * documented value from its source file, so renaming a script, changing the
 * test output directory or moving the dev port breaks here instead of only in
 * a reader's terminal. Same idea as api/demo's *DocsTests: plain file reads,
 * patterns loose enough that rewording prose keeps passing.
 */

interface ConsolePackageJson {
  scripts: Record<string, string>;
}

/**
 * The console folder, found by walking up from this test file (which runs
 * compiled under dist-test/) so the assertions do not depend on the working
 * directory npm happens to use.
 */
function findConsoleDir(): string {
  for (let dir = dirname(fileURLToPath(import.meta.url)); ; dir = dirname(dir)) {
    if (
      existsSync(join(dir, "package.json")) &&
      existsSync(join(dir, "README.md")) &&
      existsSync(join(dir, "vite.config.ts"))
    ) {
      return dir;
    }
    assert.notEqual(dir, dirname(dir), "no console folder above the test file");
  }
}

const consoleDir = findConsoleDir();
const pkg = JSON.parse(
  readFileSync(join(consoleDir, "package.json"), "utf8"),
) as ConsolePackageJson;
const consoleReadme = readFileSync(join(consoleDir, "README.md"), "utf8");
const rootReadme = readFileSync(join(consoleDir, "..", "README.md"), "utf8");
const viteConfig = readFileSync(join(consoleDir, "vite.config.ts"), "utf8");

/** Script names invoked as `npm --prefix console run <name>`. */
function runCommands(document: string): string[] {
  return [...document.matchAll(/npm --prefix console run ([a-z][a-z0-9-]*)/g)].map(
    (match) => match[1],
  );
}

/** Script names documented as "- `name` —" bullets (the "Other scripts" list). */
function bulletScripts(document: string): string[] {
  return [...document.matchAll(/^- `([a-z][a-z0-9-]*)` — /gm)].map((match) => match[1]);
}

test("every npm run command in the READMEs names a real package.json script", () => {
  const documented = [
    ...runCommands(consoleReadme),
    ...runCommands(rootReadme),
    ...bulletScripts(consoleReadme),
  ];
  // Guard the extraction itself: if the READMEs stop using either shape, an
  // empty list would otherwise pass silently.
  assert.ok(documented.includes("dev"), "the dev command is documented in a README");
  assert.ok(bulletScripts(consoleReadme).length > 0, "the script bullets are extracted");
  for (const name of documented) {
    assert.ok(name in pkg.scripts, `"${name}" is documented but is not a package.json script`);
  }
});

test("every package.json script is documented in console/README.md", () => {
  for (const name of Object.keys(pkg.scripts)) {
    assert.match(
      consoleReadme,
      new RegExp(`\\b${name}\\b`),
      `the "${name}" script is missing from console/README.md`,
    );
  }
});

test("the test command in console/README.md matches the package.json test script", () => {
  assert.match(
    consoleReadme,
    /npm --prefix console (?:run )?test\b/,
    "console/README.md documents how to run the tests",
  );

  const glob = /node --test "([^"]+)"/.exec(pkg.scripts.test);
  assert.ok(glob, "the test script runs node --test with a quoted glob");
  assert.ok(
    consoleReadme.includes(glob[1]),
    `console/README.md names the "${glob[1]}" glob from the test script`,
  );

  const tsconfig = /tsc -p (\S+\.json)/.exec(pkg.scripts.test);
  assert.ok(tsconfig, "the test script compiles with a tsconfig file");
  assert.ok(
    consoleReadme.includes(tsconfig[1]),
    `console/README.md names ${tsconfig[1]} from the test script`,
  );
  assert.ok(existsSync(join(consoleDir, tsconfig[1])), `${tsconfig[1]} exists`);
});

test("the documented dev URL matches the base and port in vite.config.ts", () => {
  const base = /base:\s*"([^"]+)"/.exec(viteConfig);
  const port = /port:\s*(\d+)/.exec(viteConfig);
  assert.ok(base, "vite.config.ts sets a base path");
  assert.ok(port, "vite.config.ts sets a dev server port");

  const url = `http://localhost:${port[1]}${base[1]}`;
  assert.ok(consoleReadme.includes(url), `console/README.md links to ${url}`);
  assert.ok(rootReadme.includes(url), `the root README links to ${url}`);
});

test("the documented API proxy settings match vite.config.ts", () => {
  const target = /process\.env\.CONSOLE_API_URL\s*\?\?\s*"([^"]+)"/.exec(viteConfig);
  assert.ok(target, "vite.config.ts defaults the proxy target after CONSOLE_API_URL");
  for (const [file, doc] of [
    ["console/README.md", consoleReadme],
    ["README.md", rootReadme],
  ] as const) {
    assert.ok(doc.includes("CONSOLE_API_URL"), `${file} documents CONSOLE_API_URL`);
    assert.ok(doc.includes(target[1]), `${file} names the default proxy target ${target[1]}`);
  }
});

test("the root README installs console dependencies before the dev command", () => {
  const install = rootReadme.indexOf("npm --prefix console install");
  const dev = rootReadme.indexOf("npm --prefix console run dev");
  assert.notEqual(install, -1, "the root README includes the dependency install step");
  assert.notEqual(dev, -1, "the root README includes the dev command");
  assert.ok(install < dev, "dependencies must be installed before the dev server runs");
});
