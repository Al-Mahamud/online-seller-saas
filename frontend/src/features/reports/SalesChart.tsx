import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Section } from "@/components/layout/Section";
import { addDays } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type SalesRow, salesQuery } from "./api";

/** What a day's sales come to, delivery included: the same figure as the "sales" tile. */
const sold = (row: SalesRow) => row.value + (row.deliveryCharged ?? 0);

/**
 * Seven days of sales ending today, one column a day. A column is a button: tapping it looks at that
 * day. The line above says what the hovered or chosen column stands for, which is also how a phone
 * reads a value, and each button says the same to a screen reader.
 */
export function SalesChart({
  today,
  chosen,
  onChoose,
}: {
  today: string;
  chosen: string;
  onChoose: (day: string) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [hovered, setHovered] = useState<string>();
  const { data } = useQuery(salesQuery(addDays(today, -6), today, "day"));
  const rows = data?.rows ?? [];
  const highest = Math.max(1, ...rows.map(sold));
  const shown = rows.find((row) => row.key === (hovered ?? chosen));
  const dayText = (row: SalesRow) =>
    `${format.fullDate(row.key)}: ${format.taka(sold(row))}, ${t("customers.orderCount", {
      count: format.number(row.orders ?? 0),
    })}`;
  return (
    <Section title={t("today.lastSevenDays")}>
      <p className="min-h-6 text-sm text-muted-foreground" aria-live="polite">
        {shown && dayText(shown)}
      </p>
      <div className="flex h-40 items-stretch gap-0.5 border-b border-input">
        {rows.map((row) => {
          const isChosen = row.key === chosen;
          const height = sold(row) === 0 ? 0 : Math.max(3, (sold(row) / highest) * 100);
          return (
            <button
              key={row.key}
              type="button"
              aria-pressed={isChosen}
              aria-label={dayText(row)}
              className="flex flex-1 cursor-pointer items-end justify-center rounded-t-md hover:bg-muted"
              onClick={() => onChoose(row.key)}
              onMouseEnter={() => setHovered(row.key)}
              onMouseLeave={() => setHovered(undefined)}
            >
              <span className="block w-full max-w-6 rounded-t bg-primary" style={{ height: `${height}%` }} />
            </button>
          );
        })}
      </div>
      <div aria-hidden="true" className="-mt-3 flex gap-0.5">
        {rows.map((row) => (
          <span
            key={row.key}
            className={cn(
              "flex-1 text-center text-[13px] text-muted-foreground",
              row.key === chosen && "font-bold text-foreground underline underline-offset-4",
            )}
          >
            {format.weekday(row.key)}
          </span>
        ))}
      </div>
    </Section>
  );
}
