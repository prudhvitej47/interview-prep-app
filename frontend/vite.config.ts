/// <reference types="vitest/config" />
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

import { fontFaces } from "./sketchFonts";

/**
 * The fonts sketches are lettered in: Excalifont (hand-drawn, the default) and Nunito (the "clean"
 * style for dense architecture pictures). Excalidraw can embed a font inside each SVG, but only
 * through a font-subsetting engine of about 735 kB compressed; instead Sketch.tsx registers these
 * faces on the page once (FontFace) and the SVGs name them. The app loads over a private network with
 * no outside requests, so the files are served from our own /assets, copied from the pinned package on
 * every build rather than committed, and so always matching the library that draws with them. The
 * faces go to the page as data, not CSS: a CSS url() to a file the build emits itself makes Vite warn,
 * once per file, that it "didn't resolve at build time".
 */
const EXCALIDRAW_PROD = fileURLToPath(new URL("./node_modules/@excalidraw/excalidraw/dist/prod/", import.meta.url));
const SKETCH_FONTS = ["Excalifont", "Nunito"];
const FONTS_URL = "assets/excalidraw/fonts/";
const FONTS_MODULE = "virtual:sketch-fonts";
const fontFile = (family: string) => new RegExp(`^${family}-Regular-[A-Za-z0-9]+\\.woff2$`);
const filesOf = (family: string) => readdirSync(`${EXCALIDRAW_PROD}fonts/${family}/`).filter((name) => fontFile(family).test(name));

/** Every face of every sketch font, each with the unicode-range the package gives it. */
function sketchFontFaces() {
  const code = readdirSync(EXCALIDRAW_PROD).filter((name) => name.endsWith(".js"))
    .map((name) => readFileSync(EXCALIDRAW_PROD + name, "utf8")).join("\n");
  return SKETCH_FONTS.flatMap((family) => {
    const files = filesOf(family);
    if (files.length === 0) throw new Error(`${family}: no font files in the Excalidraw package`);
    return fontFaces(family, files, code, `/${FONTS_URL}${family}/`);
  });
}

function sketchFonts(): Plugin {
  return {
    name: "sketch-fonts",
    resolveId(id) {
      return id === FONTS_MODULE ? "\0" + FONTS_MODULE : undefined;
    },
    load(id) {
      return id === "\0" + FONTS_MODULE ? `export default ${JSON.stringify(sketchFontFaces())};` : undefined;
    },
    // The dev server serves the files straight from the package.
    configureServer(server) {
      server.middlewares.use(`/${FONTS_URL}`, (req, res, next) => {
        const [family, file] = (req.url ?? "").split("?")[0].replace(/^\//, "").split("/");
        if (!SKETCH_FONTS.includes(family) || !fontFile(family).test(file ?? "")) return next();
        res.setHeader("Content-Type", "font/woff2");
        res.end(readFileSync(`${EXCALIDRAW_PROD}fonts/${family}/${file}`));
      });
    },
    // A build copies them into dist/assets, which Spring serves.
    generateBundle() {
      for (const family of SKETCH_FONTS) {
        for (const file of filesOf(family)) {
          this.emitFile({ type: "asset", fileName: `${FONTS_URL}${family}/${file}`,
            source: readFileSync(`${EXCALIDRAW_PROD}fonts/${family}/${file}`) });
        }
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), sketchFonts()],
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
