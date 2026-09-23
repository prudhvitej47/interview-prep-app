// Checks that every unit page actually lays out, in a real browser, at more than one width.
//
//   node tools/layout-check.mjs --bundle ../interview-prep-content/dist/content.json
//
// The unit tests check components in jsdom, which has no layout at all; check_mermaid.mjs in the
// content repository only *parses* diagrams. Neither can see a diagram that draws as unreadable
// three-pixel text, a table that runs off the page on a phone, or a diagram that failed to draw and
// left its source behind. This script opens the real pages in headless Chrome and looks.
//
// It needs the app running with the bundle you want to check:
//
//   DB_URL=… APP_CONTENT_BUNDLE_DIR=…/dist java -jar backend/target/interview-prep-backend-*.jar \
//     --server.port=8091
//
// Exit code is 1 when anything fails, so it can gate a pull request later.
import { readFileSync } from "node:fs";
import { spawn } from "node:child_process";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

// Phone first: almost every layout problem shows up at 390px before it shows up at 1000px.
const VIEWPORTS = [
  { name: "phone", width: 390, height: 844, mobile: true },
  { name: "desktop", width: 1000, height: 1400, mobile: false },
];

// Smaller than this and a diagram's labels cannot be read. Mermaid draws at ~12–16px and the page
// scales the SVG down to fit, so the number that matters is the size after scaling.
const MIN_DIAGRAM_TEXT_PX = 8.5;

function arg(name, fallback = null) {
  const at = process.argv.indexOf(`--${name}`);
  return at === -1 ? fallback : process.argv[at + 1];
}

const base = arg("base", "http://localhost:8091");
const bundle = arg("bundle", "../interview-prep-content/dist/content.json");
const only = arg("only");            // comma-separated unit ids, or a file of them
const scheme = arg("scheme", "both"); // dark | light | both
const port = Number(arg("port", "9500"));

function unitIds() {
  if (only) {
    const raw = only.includes("/") || only.endsWith(".txt") ? readFileSync(only, "utf8") : only;
    return raw.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
  }
  return JSON.parse(readFileSync(bundle, "utf8")).units.map((u) => u.id);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connect() {
  const chrome = spawn(CHROME, [
    "--headless=new",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=/tmp/layout-check-${port}`,
    "--window-size=1000,1400",
    "about:blank",
  ], { stdio: "ignore" });
  let targets;
  for (let i = 0; i < 60; i++) {
    try {
      targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      break;
    } catch {
      await sleep(250);
    }
  }
  if (!targets) throw new Error("Chrome did not start");
  const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((resolve) => (ws.onopen = resolve));
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (m) => {
    const message = JSON.parse(m.data);
    if (waiting.has(message.id)) {
      waiting.get(message.id)(message);
      waiting.delete(message.id);
    }
  };
  const send = (method, params = {}) => new Promise((resolve) => {
    const n = ++id;
    waiting.set(n, resolve);
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const evaluate = async (expression) => {
    const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    return result.result?.result?.value;
  };
  return { chrome, send, evaluate };
}

// Runs inside the page. Returns everything one load can tell us about the layout.
const INSPECT = `(() => {
  const unit = document.querySelector('.unit, .how-it-works, main article') || document.body;
  const limit = unit.getBoundingClientRect().right;
  const tooWide = [];
  // A <pre> or a .diagram is allowed to scroll inside itself; anything else must fit the column.
  const scrollsItself = (el) => el.closest('pre, .diagram, .diagram-source, table') !== null;
  for (const el of unit.querySelectorAll('*')) {
    if (scrollsItself(el)) continue;
    const box = el.getBoundingClientRect();
    if (box.width > 0 && box.right > limit + 2) {
      tooWide.push((el.tagName.toLowerCase()) + (el.className ? '.' + String(el.className).split(' ')[0] : '')
        + ' by ' + Math.round(box.right - limit) + 'px');
    }
  }
  // Diagram text, measured after the page has scaled the SVG down.
  const diagrams = [...document.querySelectorAll('.diagram svg')].map((svg) => {
    const box = svg.getBoundingClientRect();
    const viewBox = svg.viewBox?.baseVal?.width || box.width;
    const scale = viewBox > 0 ? box.width / viewBox : 1;
    let smallest = Infinity;
    for (const text of svg.querySelectorAll('text, tspan')) {
      if (!text.textContent.trim()) continue;
      const size = parseFloat(getComputedStyle(text).fontSize) * scale;
      if (size > 0) smallest = Math.min(smallest, size);
    }
    return { width: Math.round(box.width), smallestText: smallest === Infinity ? null : Math.round(smallest * 10) / 10 };
  });
  return JSON.stringify({
    pageScrolls: document.documentElement.scrollWidth > window.innerWidth + 1,
    tooWide: tooWide.slice(0, 5),
    diagrams,
    sourceFallbacks: document.querySelectorAll('.diagram-source').length,
    undrawn: document.querySelectorAll('.diagram[aria-busy="true"]').length,
    alerts: [...document.querySelectorAll('[role=alert]')].map((a) => a.textContent.trim().slice(0, 80)),
    headings: document.querySelectorAll('.unit h2, .unit h3').length,
  });
})()`;

async function main() {
  const ids = unitIds();
  const schemes = scheme === "both" ? ["dark", "light"] : [scheme];
  const { chrome, send, evaluate } = await connect();
  await send("Page.enable");

  const problems = [];
  let checked = 0;
  for (const viewport of VIEWPORTS) {
    for (const colours of schemes) {
      await send("Emulation.setDeviceMetricsOverride", {
        width: viewport.width, height: viewport.height, deviceScaleFactor: 1, mobile: viewport.mobile,
      });
      await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: colours }] });
      for (const id of ids) {
        await send("Page.navigate", { url: `${base}/units/${id}` });
        // Wait for the unit, then for its diagrams: Mermaid draws after the page appears.
        for (let i = 0; i < 60; i++) {
          if (await evaluate(`!!document.querySelector('.unit h2')`)) break;
          await sleep(200);
        }
        for (let i = 0; i < 40; i++) {
          const pending = await evaluate(`document.querySelectorAll('.diagram[aria-busy="true"]').length`);
          if (pending === 0) break;
          await sleep(250);
        }
        await sleep(150);
        const report = JSON.parse(await evaluate(INSPECT));
        checked++;
        const where = `${id} [${viewport.name}/${colours}]`;
        if (report.sourceFallbacks > 0) problems.push(`${where}: ${report.sourceFallbacks} diagram(s) showed source instead of a picture`);
        if (report.undrawn > 0) problems.push(`${where}: ${report.undrawn} diagram(s) never finished drawing`);
        if (report.alerts.length > 0) problems.push(`${where}: alert on the page - ${report.alerts[0]}`);
        if (report.pageScrolls) problems.push(`${where}: the page scrolls sideways`);
        for (const offender of report.tooWide) problems.push(`${where}: ${offender} wider than the column`);
        report.diagrams.forEach((d, i) => {
          if (d.smallestText !== null && d.smallestText < MIN_DIAGRAM_TEXT_PX) {
            problems.push(`${where}: diagram ${i + 1} draws text at ${d.smallestText}px, below ${MIN_DIAGRAM_TEXT_PX}px`);
          }
        });
        if (report.headings === 0) problems.push(`${where}: no headings rendered - did the page load?`);
      }
    }
  }

  chrome.kill();
  console.log(`\n${checked} page load(s): ${ids.length} unit(s) x ${VIEWPORTS.length} width(s) x ${schemes.length} scheme(s)`);
  if (problems.length === 0) {
    console.log("no layout problems");
    return 0;
  }
  console.log(`${problems.length} problem(s):`);
  for (const problem of problems) console.log("  " + problem);
  return 1;
}

process.exit(await main());
