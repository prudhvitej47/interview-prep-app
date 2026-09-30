/**
 * Reads the fonts a sketch can use out of the pinned Excalidraw package: which files each family has,
 * and the unicode-range each file covers, so the page can register them once and a browser fetches
 * only the files for the characters on screen. The ranges live only in the package's own bundled
 * code, written either inline or as a named constant, so they are read from there; the build fails
 * loudly if that stops matching, as an upgrade could make happen. Used by vite.config.ts.
 */
export interface FontFaceSpec {
  family: string;
  url: string;
  unicodeRange: string;
  weight?: string;
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function fontFaces(family: string, files: string[], code: string, urlPrefix: string): FontFaceSpec[] {
  return files.map((file) => {
    const variable = new RegExp(`var ([\\w$]+)="\\./fonts/${escape(family)}/${escape(file)}"`).exec(code)?.[1];
    const descriptors = variable
      && new RegExp(`\\{uri:${escape(variable)},descriptors:\\{([^}]*)\\}`).exec(code)?.[1];
    let range: string | undefined;
    if (descriptors) {
      const literal = /unicodeRange:"([^"]+)"/.exec(descriptors)?.[1];
      const named = /unicodeRange:([\w$]+)\.(\w+)/.exec(descriptors);
      range = literal ?? (named ? new RegExp(`${escape(named[1])}=\\{(?:[^}]*?,)?${named[2]}:"([^"]+)"`).exec(code)?.[1] : undefined);
    }
    if (!range) throw new Error(`${family}: no unicode-range found for ${file}; check the package's font list`);
    const weight = descriptors ? /weight:"(\d+)"/.exec(descriptors)?.[1] : undefined;
    return { family, url: urlPrefix + file, unicodeRange: range, ...(weight ? { weight } : {}) };
  });
}
