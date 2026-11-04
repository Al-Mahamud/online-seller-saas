import { afterEach, describe, expect, it, vi } from "vitest";
import { randomId } from "./ids";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("randomId", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("is a version 4 UUID", () => {
    expect(randomId()).toMatch(UUID_V4);
  });

  it("works on a plain-http address, where the browser has no randomUUID", () => {
    vi.stubGlobal("crypto", { getRandomValues: crypto.getRandomValues.bind(crypto) });
    const ids = new Set(Array.from({ length: 50 }, () => randomId()));
    expect(ids.size).toBe(50);
    for (const id of ids) {
      expect(id).toMatch(UUID_V4);
    }
  });
});
