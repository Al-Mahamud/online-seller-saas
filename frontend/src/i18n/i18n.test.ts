import { describe, expect, it } from "vitest";
import bn from "./bn.json";
import en from "./en.json";
import activityBn from "./pages/activity.bn.json";
import activityEn from "./pages/activity.en.json";
import payBn from "./pages/pay.bn.json";
import payEn from "./pages/pay.en.json";
import teamBn from "./pages/team.bn.json";
import teamEn from "./pages/team.en.json";
import twoFactorBn from "./pages/twoFactor.bn.json";
import twoFactorEn from "./pages/twoFactor.en.json";

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

  it("have the same keys in Bangla and English for texts that load with a page", () => {
    expect(keys(activityEn).sort()).toEqual(keys(activityBn).sort());
    expect(keys(teamEn).sort()).toEqual(keys(teamBn).sort());
    expect(keys(payEn).sort()).toEqual(keys(payBn).sort());
    expect(keys(twoFactorEn).sort()).toEqual(keys(twoFactorBn).sort());
  });

  it("have no empty text", () => {
    for (const [language, texts] of Object.entries({
      bn,
      en,
      activityBn,
      activityEn,
      teamBn,
      teamEn,
      payBn,
      payEn,
      twoFactorBn,
      twoFactorEn,
    })) {
      for (const key of keys(texts)) {
        const text = key.split(".").reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], texts);
        expect(text, `${language}: ${key}`).not.toBe("");
      }
    }
  });
});
