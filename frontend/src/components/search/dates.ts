import { addDays, dhakaToday } from "@/lib/dates";

export type Preset = "today" | "yesterday" | "last7" | "last30" | "thisMonth" | "lastMonth";
export const PRESETS: Preset[] = ["today", "yesterday", "last7", "last30", "thisMonth", "lastMonth"];

/** The Dhaka days a quick pick stands for, both included. */
export function presetRange(preset: Preset, today = dhakaToday()): { from: string; to: string } {
  const firstOfMonth = `${today.slice(0, 8)}01`;
  switch (preset) {
    case "today":
      return { from: today, to: today };
    case "yesterday":
      return { from: addDays(today, -1), to: addDays(today, -1) };
    case "last7":
      return { from: addDays(today, -6), to: today };
    case "last30":
      return { from: addDays(today, -29), to: today };
    case "thisMonth":
      return { from: firstOfMonth, to: today };
    case "lastMonth": {
      const lastDay = addDays(firstOfMonth, -1);
      return { from: `${lastDay.slice(0, 8)}01`, to: lastDay };
    }
  }
}

/** The quick pick these days are, if any. */
export function presetOf(from: string | undefined, to: string | undefined, today = dhakaToday()): Preset | undefined {
  return PRESETS.find((preset) => {
    const range = presetRange(preset, today);
    return range.from === from && range.to === to;
  });
}
