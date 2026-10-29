import { describe, expect, it } from "vitest";
import bn from "./bn.json";
import en from "./en.json";

function keys(value: unknown, prefix = ""): string[] {
  if (typeof value !== "object" || value === null) {
    return [prefix];
  }
  return Object.entries(value).flatMap(([key, inner]) => keys(inner, prefix ? `${prefix}.${key}` : key));
}

describe("translations", () => {
  it("have the same keys in Bangla and English", () => {
    expect(keys(en).sort()).toEqual(keys(bn).sort());
  });

  it("have no empty text", () => {
    for (const [language, texts] of Object.entries({ bn, en })) {
      for (const key of keys(texts)) {
        const text = key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], texts);
        expect(text, `${language}: ${key}`).not.toBe("");
      }
    }
  });
});
