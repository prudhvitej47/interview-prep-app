import { describe, expect, it } from "vitest";
import { fontFaces } from "./sketchFonts";

describe("reading the font faces out of the Excalidraw package", () => {
  it("reads a range written inline", () => {
    const code = 'var a="./fonts/Excalifont/Excalifont-Regular-ab12.woff2";var s=[{uri:a,descriptors:{unicodeRange:"U+20-7e"}}]';
    expect(fontFaces("Excalifont", ["Excalifont-Regular-ab12.woff2"], code, "/f/")).toEqual([
      { family: "Excalifont", url: "/f/Excalifont-Regular-ab12.woff2", unicodeRange: "U+20-7e" },
    ]);
  });

  it("reads a range kept in a named constant, and the weight", () => {
    const code = 'tn={LATIN:"U+0000-00FF",LATIN_EXT:"U+0100-02AF"};var uc="./fonts/Nunito/Nunito-Regular-Xy9.woff2";'
      + 'var q=[{uri:uc,descriptors:{unicodeRange:tn.LATIN_EXT,weight:"500"}}]';
    expect(fontFaces("Nunito", ["Nunito-Regular-Xy9.woff2"], code, "/n/")).toEqual([
      { family: "Nunito", url: "/n/Nunito-Regular-Xy9.woff2", unicodeRange: "U+0100-02AF", weight: "500" },
    ]);
  });

  it("fails the build when a file has no range, as an upgrade could make happen", () => {
    expect(() => fontFaces("Nunito", ["Nunito-Regular-Q.woff2"], "nothing here", "/n/"))
      .toThrow("Nunito: no unicode-range found for Nunito-Regular-Q.woff2");
  });
});
