import { Search, X } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Field } from "@/components/form/Field";
import { Input } from "@/components/ui/input";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";

/** What a list is searched by. */
export interface DatedSearchValue {
  q?: string;
  from?: string;
  to?: string;
}

export const ON_DARK_FOCUS =
  "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text";

function SearchInput({
  label,
  hint,
  value,
  onChange,
}: {
  label: string;
  hint: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="relative min-w-0 flex-1">
      <Search aria-hidden="true" className="absolute top-1/2 left-3.5 size-5 -translate-y-1/2 text-muted-foreground" />
      <Input
        type="search"
        aria-label={label}
        placeholder={hint}
        className={cn(
          "h-12.5 rounded-xl border-0 bg-card pl-11 text-base text-foreground md:h-13 md:text-[17px]",
          ON_DARK_FOCUS,
        )}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function SearchButtons({ onReset }: { onReset: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="grid flex-[1_1_100%] grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2 md:ml-auto md:flex md:flex-none">
      <button
        type="button"
        className={cn(
          "h-12 cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text text-base font-semibold text-white hover:bg-white/10 md:h-11 md:px-4 md:text-[15px]",
          ON_DARK_FOCUS,
        )}
        onClick={onReset}
      >
        {t("products.list.reset")}
      </button>
      <button
        type="submit"
        className={cn(
          "flex h-12 cursor-pointer items-center justify-center gap-1.5 rounded-[10px] bg-marigold-text text-base font-bold text-ledger hover:bg-marigold md:h-11 md:px-4.5 md:text-[15px]",
          ON_DARK_FOCUS,
        )}
      >
        <Search aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
        {t("products.list.searchButton")}
      </button>
    </div>
  );
}

/** A text box with the buttons to search and reset, on a dark ground, for a list with nothing else to filter by. */
export function TextSearchPanel({
  value,
  label,
  hint,
  className,
  onSearch,
  onReset,
}: {
  value: string;
  label: string;
  hint: string;
  className?: string;
  onSearch: (value: string) => void;
  onReset: () => void;
}) {
  const [q, setQ] = useState(value);
  // The address can change without this panel: a chip removed, the browser's back button, a shared link.
  useEffect(() => setQ(value), [value]);
  return (
    // biome-ignore lint/a11y/useSemanticElements: a form with this role is how a search with a button is announced.
    <form
      role="search"
      aria-label={label}
      className={cn("flex flex-wrap items-center gap-2.5 md:flex-nowrap md:gap-3", className)}
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(q.trim());
      }}
    >
      {/* On phones the box has its own row, above the buttons. */}
      <div className="flex min-w-0 flex-[1_1_100%] md:flex-1">
        <SearchInput label={label} hint={hint} value={q} onChange={setQ} />
      </div>
      <SearchButtons onReset={onReset} />
    </form>
  );
}

/**
 * A text box, two dates, and the buttons to search and reset, on a dark ground. What's typed waits
 * here; the list follows the address, which changes on "search".
 */
export function DatedSearchPanel({
  value,
  label,
  hint,
  fromLabel,
  toLabel,
  className,
  children,
  onSearch,
  onReset,
}: {
  value: DatedSearchValue;
  label: string;
  hint: string;
  fromLabel: string;
  toLabel: string;
  className?: string;
  children?: ReactNode;
  onSearch: (value: DatedSearchValue) => void;
  onReset: () => void;
}) {
  const [q, setQ] = useState(value.q ?? "");
  const [from, setFrom] = useState(value.from ?? "");
  const [to, setTo] = useState(value.to ?? "");
  // The address can change without this panel: a chip removed, the browser's back button, a shared link.
  useEffect(() => {
    setQ(value.q ?? "");
    setFrom(value.from ?? "");
    setTo(value.to ?? "");
  }, [value.q, value.from, value.to]);
  return (
    // biome-ignore lint/a11y/useSemanticElements: a form with this role is how a search with a button is announced.
    <form
      role="search"
      aria-label={label}
      className={cn("flex flex-col gap-2.5 md:gap-3", className)}
      onSubmit={(event) => {
        event.preventDefault();
        onSearch({ q: q.trim() || undefined, from: from || undefined, to: to || undefined });
      }}
    >
      {children}
      <SearchInput label={label} hint={hint} value={q} onChange={setQ} />
      <div className="flex flex-wrap items-end gap-2.5 md:gap-3">
        <div className="grid min-w-0 flex-[2_1_100%] grid-cols-2 gap-2.5 md:flex-[2_1_340px] md:gap-3 [&_input]:h-11.5 [&_input]:rounded-[10px] [&_input]:border-0 [&_input]:bg-card [&_input]:px-2 [&_input]:text-[15px] [&_input]:text-foreground md:[&_input]:h-11 [&_label]:text-sm [&_label]:text-ledger-text">
          <Field label={fromLabel} type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          <Field label={toLabel} type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        </div>
        <SearchButtons onReset={onReset} />
      </div>
    </form>
  );
}

/** What the list is searching for, each with a way to take it off. */
export function SearchChips({
  value,
  onRemove,
}: {
  value: DatedSearchValue;
  onRemove: (key: keyof DatedSearchValue) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const chips = [
    value.q && { key: "q" as const, text: `"${value.q}"` },
    value.from && { key: "from" as const, text: t("orders.list.fromChip", { date: format.date(value.from) }) },
    value.to && { key: "to" as const, text: t("orders.list.toChip", { date: format.date(value.to) }) },
  ].filter((chip) => !!chip);
  if (chips.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((chip) => (
        <button
          key={chip.key}
          type="button"
          aria-label={t("products.list.removeFilter", { name: chip.text })}
          className="flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full bg-accent py-1 pr-2.5 pl-3 text-sm font-semibold text-accent-foreground hover:bg-[#D5E9DF]"
          onClick={() => onRemove(chip.key)}
        >
          {chip.text}
          <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
        </button>
      ))}
    </div>
  );
}
