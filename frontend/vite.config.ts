/// <reference types="vitest/config" />
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

/**
 * The hand-drawn font sketches are lettered in. Excalidraw can embed the font inside each SVG, but
 * only through a font-subsetting engine of about 735 kB compressed; instead the page declares
 * Excalifont once with @font-face and the SVGs name it. The app loads over a private network with
 * no outside requests, so the files are served from our own /assets, copied from the pinned package
 * on every build rather than committed, and so always matching the library that draws with them.
 * Must agree with SKETCH_ASSET_PATH in src/unit/Sketch.tsx.
 */
const EXCALIDRAW_PROD = fileURLToPath(new URL("./node_modules/@excalidraw/excalidraw/dist/prod/", import.meta.url));
const EXCALIFONT_DIR = EXCALIDRAW_PROD + "fonts/Excalifont/";
const EXCALIFONT_URL = "assets/excalidraw/fonts/Excalifont/";
const EXCALIFONT_FILE = /^Excalifont-Regular-[0-9a-f]+\.woff2$/;
const EXCALIFONT_CSS = "virtual:excalifont.css";

/**
 * One @font-face per file, each with the unicode-range the package gives it, so a browser fetches
 * only the files for the characters on the page (Latin is one 25 kB file). The ranges live only in
 * the package's own code, so they are read from there, and the build fails loudly if that stops
 * matching every font file, as an upgrade could make it.
 */
function excalifontCss(): string {
  const files = readdirSync(EXCALIFONT_DIR).filter((name) => EXCALIFONT_FILE.test(name));
  const code = readdirSync(EXCALIDRAW_PROD).filter((name) => name.endsWith(".js"))
    .map((name) => readFileSync(EXCALIDRAW_PROD + name, "utf8")).join("\n");
  const rules = files.map((file) => {
    const variable = new RegExp(`var (\\w+)="\\./fonts/Excalifont/${file.replace(".", "\\.")}"`).exec(code)?.[1];
    const range = variable && new RegExp(`\\{uri:${variable},descriptors:\\{unicodeRange:"([^"]+)"`).exec(code)?.[1];
    if (!range) throw new Error(`excalifont: no unicode-range found for ${file}; check the package's font list`);
    return `@font-face { font-family: "Excalifont"; src: url("/${EXCALIFONT_URL}${file}") format("woff2");`
      + ` unicode-range: ${range}; font-display: swap; }`;
  });
  if (rules.length === 0) throw new Error(`excalifont: no font files in ${EXCALIFONT_DIR}`);
  return rules.join("\n");
}

function excalifont(): Plugin {
  return {
    name: "excalifont",
    resolveId(id) {
      return id === EXCALIFONT_CSS ? "\0" + EXCALIFONT_CSS : undefined;
    },
    load(id) {
      return id === "\0" + EXCALIFONT_CSS ? excalifontCss() : undefined;
    },
    // The dev server serves the files straight from the package.
    configureServer(server) {
      server.middlewares.use(`/${EXCALIFONT_URL}`, (req, res, next) => {
        const file = (req.url ?? "").split("?")[0].replace(/^\//, "");
        if (!EXCALIFONT_FILE.test(file)) return next();
        res.setHeader("Content-Type", "font/woff2");
        res.end(readFileSync(EXCALIFONT_DIR + file));
      });
    },
    // A build copies them into dist/assets, which Spring serves.
    generateBundle() {
      for (const file of readdirSync(EXCALIFONT_DIR).filter((name) => EXCALIFONT_FILE.test(name))) {
        this.emitFile({ type: "asset", fileName: EXCALIFONT_URL + file, source: readFileSync(EXCALIFONT_DIR + file) });
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), excalifont()],
  server: {
    // In development the SPA runs on Vite's dev server and the API on Spring, so /api is
    // proxied. In production both are served by Spring from one origin and this does nothing.
    proxy: {
      "/api": "http://localhost:8080",
      "/actuator": "http://localhost:8080",
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
  },
});
