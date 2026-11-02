/** How many months can be paid for at once; a year costs ten months (the server's rule too). */
export const MONTH_CHOICES = [1, 6, 12] as const;
export type Months = (typeof MONTH_CHOICES)[number];

export function monthsPrice(priceMonthly: number, months: Months): number {
  return priceMonthly * (months === 12 ? 10 : months);
}

const DAY = 24 * 60 * 60_000;

/** Whole days from now until `at`, never below 0. */
export function daysUntil(at: string, now = Date.now()): number {
  return Math.max(0, Math.ceil((new Date(at).getTime() - now) / DAY));
}

/** A period of `months` from `start`, as the server counts it (calendar months). */
export function periodEnd(start: Date, months: Months): Date {
  const end = new Date(start);
  end.setMonth(end.getMonth() + months);
  return end;
}
