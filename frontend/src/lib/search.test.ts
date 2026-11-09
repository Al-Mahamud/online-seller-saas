import { describe, expect, it } from "vitest";
import { containsText } from "./search";

// য় as one character (U+09DF) and as two (য U+09AF + ় U+09BC); ড় likewise.
const YA_ONE = "য়";
const YA_TWO = "য়";
const RA_ONE = "ড়";
const RA_TWO = "ড়";

describe("containsText", () => {
  it("ignores case", () => {
    expect(containsText("Cotton Kurti", "KURTI")).toBe(true);
  });

  it("finds য় and ড় typed as one character or two", () => {
    expect(containsText(`কোতো${YA_ONE}ালী`, `তো${YA_TWO}া`)).toBe(true);
    expect(containsText(`কোতো${YA_TWO}ালী`, `তো${YA_ONE}া`)).toBe(true);
    expect(containsText(`বা${RA_ONE}ি`, `বা${RA_TWO}ি`)).toBe(true);
  });
});
