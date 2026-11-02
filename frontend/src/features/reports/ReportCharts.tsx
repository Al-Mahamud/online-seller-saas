import { type KeyboardEvent, type PointerEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Bucket, buckets, type DayRow, daysPerColumn, type Slice } from "./charts";

/** A pie, drawn only: what it stands for is in `label`, and beside it for those who can see it. */
export function Pie({
  shown,
  label,
  size,
  chosen,
  onChoose,
}: {
  shown: Slice[];
  label: string;
  size: number;
  chosen?: string;
  onChoose?: (key: string) => void;
}) {
  return (
    <svg role="img" aria-label={label} width={size} height={size} viewBox="0 0 200 200" className="flex-none">
      {shown.map((slice) => (
        // biome-ignore lint/a11y/noStaticElementInteractions: a pointer's shortcut to the buttons listed beside the pie.
        <path
          key={slice.key}
          d={slice.path}
          fill={slice.color}
          stroke="var(--card)"
          strokeWidth="2"
          opacity={chosen === undefined || chosen === slice.key ? 1 : 0.35}
          className={cn(onChoose && "cursor-pointer")}
          onMouseEnter={() => onChoose?.(slice.key)}
          onClick={() => onChoose?.(slice.key)}
        />
      ))}
    </svg>
  );
}

/**
 * Sales over the days chosen, one column a day, or a week or about a month when there are too many to
 * read. Thirty columns are too thin to tap one by one, so the whole chart is one control, a slider:
 * point at or tap a column, or use the arrow keys. The line above says what the chosen column stands
 * for, or the best one until then, and a screen reader hears the same.
 */
export function DayChart({ rows }: { rows: DayRow[] }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [chosen, setChosen] = useState<number>();
  const columns = buckets(rows);
  const highest = Math.max(1, ...columns.map((column) => column.value));
  const bestIndex = columns.reduce(
    (top, column, index) => (column.value > (columns[top]?.value ?? 0) ? index : top),
    0,
  );
  const best = columns[bestIndex];
  // The days chosen can change under a column that was picked.
  const shown = chosen === undefined ? undefined : columns[chosen];
  const name = (column: Bucket) =>
    column.first === column.last
      ? format.dayMonth(column.first)
      : `${format.dayMonth(column.first)}–${format.dayMonth(column.last)}`;
  const line = (column: Bucket) =>
    t("reports.columnLine", {
      day: name(column),
      amount: format.taka(column.value),
      orders: t("customers.orderCount", { count: format.number(column.orders) }),
    });
  const last = columns.length - 1;
  const atPointer = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    const index = Math.floor(((event.clientX - box.left) / box.width) * columns.length);
    setChosen(Math.max(0, Math.min(last, index)));
  };
  function move(event: KeyboardEvent) {
    const from = chosen ?? bestIndex;
    const to = {
      ArrowRight: Math.min(last, from + 1),
      ArrowUp: Math.min(last, from + 1),
      ArrowLeft: Math.max(0, from - 1),
      ArrowDown: Math.max(0, from - 1),
      Home: 0,
      End: last,
    }[event.key];
    if (to !== undefined) {
      event.preventDefault();
      setChosen(to);
    }
  }
  return (
    <div className="flex flex-col gap-1">
      <p className="min-h-5.5 text-sm text-muted-foreground">
        {shown
          ? line(shown)
          : best &&
            t(daysPerColumn(rows.length) === 1 ? "reports.bestDay" : "reports.bestSpell", {
              day: name(best),
              amount: format.taka(best.value),
            })}
      </p>
      <span aria-hidden="true" className="text-[13px] text-muted-foreground">
        {format.taka(highest)}
      </span>
      <div
        role="slider"
        tabIndex={0}
        aria-label={t("reports.chartLabel")}
        aria-orientation="horizontal"
        aria-valuemin={0}
        aria-valuemax={last}
        aria-valuenow={chosen ?? bestIndex}
        aria-valuetext={line(shown ?? best ?? { first: "", last: "", value: 0, orders: 0 })}
        className="relative flex h-37.5 cursor-pointer touch-pan-y items-stretch gap-0.5 border-b border-input focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
        onPointerMove={atPointer}
        onPointerDown={atPointer}
        onPointerLeave={(event) => {
          // A finger lifted leaves its column chosen, as there's no hover to go back to.
          if (event.pointerType === "mouse") {
            setChosen(undefined);
          }
        }}
        onKeyDown={move}
      >
        <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-0 border-t border-muted" />
        <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 top-1/2 border-t border-muted" />
        {columns.map((column, index) => (
          <div key={column.first} className="relative flex min-w-0 flex-1 items-end">
            <span
              className={cn("block w-full rounded-t-[4px]", index === chosen ? "bg-ledger" : "bg-primary")}
              style={{ height: column.value === 0 ? 0 : `${Math.max(3, (column.value / highest) * 100)}%` }}
            />
          </div>
        ))}
      </div>
      <div aria-hidden="true" className="flex justify-between text-[13px] text-muted-foreground">
        <span>{columns[0] && format.dayMonth(columns[0].first)}</span>
        <span>{columns[last] && format.dayMonth(columns[last].last)}</span>
      </div>
    </div>
  );
}
