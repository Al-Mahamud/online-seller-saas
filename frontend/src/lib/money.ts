import { latinDigits } from "./digits";

/**
 * What someone typed as taka, in whole poisha: "১,৫০০" is 150000 and "1200.50" is 120050. Null when it
 * isn't an amount, including more than two decimals, which a price can't have.
 */
export function parseTaka(text: string): number | null {
  const cleaned = latinDigits(text).replace(/[,\s৳]/g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) {
    return null;
  }
  const [whole = "0", fraction = ""] = cleaned.split(".");
  return Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
}

/** Poisha as text for an input: 150000 is "1500", and 120050 is "1200.50". */
export function takaInput(poisha: number): string {
  return poisha % 100 === 0 ? String(poisha / 100) : (poisha / 100).toFixed(2);
}

/** A whole number someone typed, in either digits: null when it isn't one. */
export function parseCount(text: string): number | null {
  const cleaned = latinDigits(text).replace(/[,\s]/g, "");
  return /^\d+$/.test(cleaned) ? Number(cleaned) : null;
}
