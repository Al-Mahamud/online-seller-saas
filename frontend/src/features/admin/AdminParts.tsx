import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { plansQuery } from "./api";
import { useAdminTexts } from "./texts";

/** The dark band each admin page opens with, as on the shop's own pages. */
export function AdminHero({
  title,
  subtitle,
  above,
  aside,
  children,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  above?: ReactNode;
  aside?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <header className="relative flex flex-col gap-3.5 overflow-hidden rounded-2xl bg-ledger py-4.5 pr-4 pl-7 text-white md:py-5.5 md:pr-6.5 md:pl-9">
      <div aria-hidden="true" className="absolute inset-y-0 left-3.5 w-0.5 bg-marigold" />
      {above}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-[26px] leading-tight font-bold break-words md:text-[30px]">{title}</h1>
          {subtitle && <div className="mt-1 text-[15px] text-ledger-text">{subtitle}</div>}
        </div>
        {aside}
      </div>
      {children}
    </header>
  );
}

/** A figure in the band: a small label over a large number. */
export function HeroFigure({ label, value, gold }: { label: string; value: string; gold?: boolean }) {
  return (
    <div>
      <dt className="text-sm font-semibold text-ledger-text">{label}</dt>
      <dd className={cn("text-2xl font-bold md:text-[28px]", gold && "text-marigold-text")}>{value}</dd>
    </div>
  );
}

/** A white card with a heading. */
export function AdminCard({
  title,
  action,
  className,
  children,
}: {
  title?: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn("flex min-w-0 flex-col gap-3 rounded-2xl border bg-card p-4 md:p-5", className)}>
      {(title || action) && (
        <div className="flex items-baseline justify-between gap-3">
          {title && <h2 className="text-lg font-bold">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

/** Plans by code, for their names; null until they arrive. */
export function usePlanNames() {
  const { data } = useQuery(plansQuery);
  return (code: string) => data?.find((plan) => plan.code === code)?.nameEn ?? code;
}

const PLAN_LOOK: Record<string, string> = {
  FREE: "bg-muted text-foreground/80",
  STARTER: "bg-accent text-accent-foreground",
  PRO: "bg-ledger text-marigold-text",
};

export function PlanBadge({ code, className }: { code: string; className?: string }) {
  const name = usePlanNames();
  return (
    <span
      className={cn(
        "inline-block rounded-full px-2.5 text-[13px] leading-6 font-bold whitespace-nowrap",
        PLAN_LOOK[code] ?? "bg-muted text-foreground/80",
        className,
      )}
    >
      {name(code)}
    </span>
  );
}

const STATE_DOT: Record<string, string> = {
  ACTIVE: "bg-primary",
  GRACE: "bg-warning",
  READ_ONLY: "bg-muted-foreground",
  SUSPENDED: "bg-destructive",
  TRIALING: "bg-primary",
};

/** A subscription's state: a dot and its name. */
export function StateLabel({ status, onDark }: { status: string; onDark?: boolean }) {
  const text = useAdminTexts();
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-sm font-semibold", onDark && "text-white")}>
      <span aria-hidden="true" className={cn("size-2 rounded-full", STATE_DOT[status] ?? "bg-muted-foreground")} />
      {text.state[status] ?? status}
    </span>
  );
}

/** Chips that narrow a list, on the dark band: one pressed at a time. */
export function HeroChips<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: { value: T | undefined; label: string }[];
  value: T | undefined;
  onChange: (value: T | undefined) => void;
}) {
  return (
    // biome-ignore lint/a11y/useSemanticElements: buttons that belong together, outside any form.
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5">
      {options.map((option) => (
        <button
          key={option.value ?? "all"}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
          className={cn(
            "min-h-9.5 cursor-pointer rounded-full border-[1.5px] px-3 text-sm font-bold",
            option.value === value
              ? "border-marigold-text bg-marigold-text text-ledger"
              : "border-ledger-outline text-white hover:bg-white/8",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
