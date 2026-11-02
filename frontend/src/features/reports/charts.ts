/** The workings of the report page's charts, kept apart from how they're drawn. */

/** Checked for lightness, contrast against a card, and colour-blind separation. Largest slice first. */
export const SLICE_COLORS = ["#0E7C5A", "#C4820F", "#2E6FB7", "#C2553D", "#7A5AA6"] as const;
export const OTHER_COLOR = "#8A918C";
export const COST_COLOR = "#2E6FB7";
export const WRITE_OFF_COLOR = "#C2553D";
export const PROFIT_COLOR = "#0E7C5A";

/** More slices than this can't be told apart: past it, the smallest share one. */
const MOST_SLICES = 6;

export interface Part {
  key: string;
  name: string;
  value: number;
  color?: string;
}

export interface Slice extends Part {
  color: string;
  /** Of the whole, from 0 to 1. */
  share: number;
  path: string;
}

/** The outline of a slice of a circle of radius 96 in a box of 200, from one share of a turn to another. */
export function slicePath(from: number, to: number): string {
  const point = (turn: number) => {
    const angle = turn * 2 * Math.PI;
    return `${(100 + 96 * Math.sin(angle)).toFixed(2)} ${(100 - 96 * Math.cos(angle)).toFixed(2)}`;
  };
  // An arc that ends where it starts draws nothing, so the whole circle is two halves.
  if (to - from >= 0.9999) {
    return `M100 4A96 96 0 1 1 100 196A96 96 0 1 1 100 4Z`;
  }
  return `M100 100L${point(from)}A96 96 0 ${to - from > 0.5 ? 1 : 0} 1 ${point(to)}Z`;
}

/** Slices for what has a value, in the order given. A part can bring its own colour. */
export function slices(parts: Part[]): Slice[] {
  const counted = parts.filter((part) => part.value > 0);
  const whole = counted.reduce((sum, part) => sum + part.value, 0);
  let turn = 0;
  return counted.map((part, index) => {
    const from = turn;
    turn += part.value / whole;
    return {
      ...part,
      color: part.color ?? SLICE_COLORS[index % SLICE_COLORS.length] ?? OTHER_COLOR,
      share: part.value / whole,
      path: slicePath(from, turn),
    };
  });
}

/** Largest first, with the smallest folded into one grey "other" when there are too many to tell apart. */
export function rankedSlices(parts: Part[], otherName: string): Slice[] {
  const ranked = parts.filter((part) => part.value > 0).sort((a, b) => b.value - a.value);
  if (ranked.length <= MOST_SLICES) {
    return slices(ranked);
  }
  const top = ranked.slice(0, MOST_SLICES - 1);
  const rest = ranked.slice(MOST_SLICES - 1).reduce((sum, part) => sum + part.value, 0);
  return slices([...top, { key: "other", name: otherName, value: rest, color: OTHER_COLOR }]);
}

export interface DayRow {
  key: string;
  value: number;
  orders?: number | null;
}

export interface Bucket {
  first: string;
  last: string;
  value: number;
  orders: number;
}

/** How many days a column stands for: 200 columns can't be read on a phone. */
export function daysPerColumn(days: number): number {
  return days <= 45 ? 1 : days <= 183 ? 7 : 30;
}

/** Days added up into columns, from the first day on. */
export function buckets(rows: DayRow[]): Bucket[] {
  const size = daysPerColumn(rows.length);
  const columns: Bucket[] = [];
  for (let index = 0; index < rows.length; index += size) {
    const part = rows.slice(index, index + size);
    columns.push({
      first: part[0]?.key ?? "",
      last: part[part.length - 1]?.key ?? "",
      value: part.reduce((sum, row) => sum + row.value, 0),
      orders: part.reduce((sum, row) => sum + (row.orders ?? 0), 0),
    });
  }
  return columns;
}

/** From a day to a day, both included. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
}

/** How this period's sales compare with the one before: null when there's nothing to compare with. */
export function changeFrom(before: number, now: number): number | null {
  return before > 0 ? Math.round(((now - before) / before) * 100) : null;
}
