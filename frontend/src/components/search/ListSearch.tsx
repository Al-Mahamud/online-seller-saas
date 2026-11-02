import { Calendar, ChevronDown, Clock, type LucideIcon, Search, SlidersHorizontal, X } from "lucide-react";
import { type ReactNode, useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { useRecentSearches } from "@/lib/recentSearches";
import { cn } from "@/lib/utils";
import { PRESETS, type Preset, presetOf, presetRange } from "./dates";
import { SEARCH_LOOK, useSearchTheme } from "./theme";

/** What a list is searched by: the text, the days, and any other filter by its key. */
export type ListSearchValue = { q?: string; from?: string; to?: string } & Record<string, string | undefined>;

/** A filter that picks one of a few values, such as a category. */
export interface ChoiceFilter {
  key: string;
  label: string;
  icon: LucideIcon;
  options: { value: string; label: string }[];
  /** The choice that means "no filter", listed first. Left out: one option is always on, `defaultValue`. */
  allLabel?: string;
  defaultValue?: string;
}

/** Days to filter by. `anyLabel` names the days shown with none chosen ("Any time", or a page's default). */
export interface DateFilter {
  anyLabel?: string;
}

const PILL =
  "flex h-11 flex-none cursor-pointer items-center gap-2 rounded-xl border-[1.5px] px-3 text-[15px] font-semibold whitespace-nowrap md:h-12";
const PILL_OFF = "border-border bg-background text-foreground hover:bg-muted";
const CHOICE =
  "min-h-11 cursor-pointer rounded-[10px] border-[1.5px] px-2 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring";
const CHOICE_ON = "border-primary bg-primary text-primary-foreground";
const CHOICE_OFF = "border-input bg-card text-foreground hover:bg-muted";

function clean(value: ListSearchValue): ListSearchValue {
  return Object.fromEntries(Object.entries(value).map(([key, text]) => [key, text || undefined]));
}

/**
 * The search panel of every list: a bar with the text, the filters and the search button; under it,
 * what's being searched by, each with a way off, and how much was found. Text waits for the button or
 * Enter; a filter picked applies at once. On phones the filters are in a sheet. Its colours are the
 * owner's choice (business settings).
 *
 * @param list names the list, for its recent searches and element ids
 * @param surface `band`: from 768 px a panel of its own; `inset`: always inside a dark header
 */
export function ListSearch({
  list,
  label,
  hint,
  value,
  dates,
  choices = [],
  count,
  aside,
  surface = "band",
  className,
  onChange,
  onReset,
}: {
  list: string;
  label: string;
  hint: string;
  value: ListSearchValue;
  dates?: DateFilter;
  choices?: ChoiceFilter[];
  count?: string;
  aside?: ReactNode;
  surface?: "band" | "inset";
  className?: string;
  onChange: (value: ListSearchValue) => void;
  onReset: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const look = SEARCH_LOOK[useSearchTheme()];
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  const { recent, remember } = useRecentSearches(list);
  const [draft, setDraft] = useState(value.q ?? "");
  const [focused, setFocused] = useState(false);
  const [sheet, setSheet] = useState(false);
  // The address can change without this panel: a chip removed, the browser's back button, a shared link.
  useEffect(() => setDraft(value.q ?? ""), [value.q]);

  // "/" anywhere on the page, outside a text box, goes to the search.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typing = target?.closest("input, textarea, select, [contenteditable=true], [role=dialog]");
      if (event.key === "/" && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
        event.preventDefault();
        input.current?.focus();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const apply = (next: ListSearchValue) => {
    const q = draft.trim();
    remember(q);
    onChange(clean({ ...value, q, ...next }));
  };
  const reset = () => {
    setDraft("");
    onReset();
  };

  const day = (at: string) => format.dayMonth(at);
  const rangeText = (from?: string, to?: string) => {
    const preset = presetOf(from, to);
    if (preset) {
      return t(`search.preset.${preset}`);
    }
    if (from && to) {
      return from === to ? day(from) : t("search.range", { from: day(from), to: day(to) });
    }
    return from ? t("search.since", { date: day(from) }) : to ? t("search.until", { date: day(to) }) : undefined;
  };
  const dateText = dates ? rangeText(value.from, value.to) : undefined;
  const choiceText = (choice: ChoiceFilter) =>
    choice.options.find((option) => option.value === (value[choice.key] ?? choice.defaultValue))?.label;
  const chips = [
    value.q && {
      key: "q",
      text: `"${value.q}"`,
      remove: () => {
        setDraft("");
        onChange({ ...value, q: undefined });
      },
    },
    dateText && { key: "dates", text: dateText, remove: () => onChange({ ...value, from: undefined, to: undefined }) },
    ...choices.map(
      (choice) =>
        value[choice.key] &&
        value[choice.key] !== choice.defaultValue && {
          key: choice.key,
          text: choiceText(choice) ?? "",
          remove: () => onChange({ ...value, [choice.key]: undefined }),
        },
    ),
  ].filter((chip) => !!chip);
  const filtersOn = chips.filter((chip) => chip.key !== "q").length;
  const hasFilters = !!dates || choices.length > 0;
  const suggest = focused && draft === "" && recent.length > 0;

  return (
    <div
      className={cn(
        "flex flex-col gap-2.5 md:gap-3",
        surface === "band" ? [look.band, look.phoneCard] : look.card,
        className,
      )}
    >
      {surface === "band" && (
        <div aria-hidden="true" className={cn("absolute inset-y-0 left-7 hidden w-0.5 md:block", look.line)} />
      )}
      {/* biome-ignore lint/a11y/useSemanticElements: a form with this role is how a search with a button is announced. */}
      <form
        role="search"
        aria-label={label}
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setFocused(false);
          apply({});
        }}
      >
        {/* biome-ignore lint/a11y/noStaticElementInteractions: it only notices focus moving among its own buttons, for the recent searches. */}
        <div
          ref={bar}
          className={cn(
            "relative flex min-w-0 flex-1 items-center gap-1.5 rounded-2xl bg-card py-1 pr-1 pl-3 text-foreground md:py-1.5 md:pr-1.5 md:pl-4",
            look.ring,
          )}
          onFocus={(event) =>
            // Only the text box and its own suggestions keep them open: not a filter closing back onto the bar.
            setFocused(event.target === input.current || event.target.hasAttribute("data-suggestion"))
          }
          onBlur={(event) => {
            if (!bar.current?.contains(event.relatedTarget as Node | null)) {
              setFocused(false);
            }
          }}
        >
          <Search aria-hidden="true" className="size-5 flex-none text-primary" strokeWidth={2.2} />
          <input
            ref={input}
            id={`${id}-q`}
            type="search"
            aria-label={label}
            placeholder={hint}
            autoComplete="off"
            enterKeyHint="search"
            className="h-11 min-w-0 flex-1 bg-card px-1 text-base outline-none placeholder:text-[#6b746e] md:h-12 md:text-[17px] [&::-webkit-search-cancel-button]:hidden"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown" && suggest) {
                event.preventDefault();
                bar.current?.querySelector<HTMLButtonElement>("[data-suggestion]")?.focus();
              }
              if (event.key === "Escape") {
                setFocused(false);
              }
            }}
          />
          {draft ? (
            <button
              type="button"
              aria-label={t("search.clearText")}
              className="flex size-9 flex-none cursor-pointer items-center justify-center rounded-[10px] bg-muted text-[#3e4842] hover:bg-border"
              onClick={() => {
                setDraft("");
                input.current?.focus();
              }}
            >
              <X aria-hidden="true" className="size-4.5" strokeWidth={2.4} />
            </button>
          ) : (
            !focused && (
              <span
                aria-hidden="true"
                className="hidden h-6.5 min-w-6.5 flex-none items-center justify-center rounded-md border border-input border-b-2 font-sans text-sm font-semibold text-muted-foreground md:inline-flex"
              >
                /
              </span>
            )
          )}
          {hasFilters && <span aria-hidden="true" className="mx-1.5 hidden h-8 w-px flex-none bg-border md:block" />}
          {dates && (
            <div className="hidden md:block">
              <DatePill
                value={value}
                text={dateText ?? dates.anyLabel ?? t("search.anyTime")}
                anyLabel={dates.anyLabel ?? t("search.anyTime")}
                on={!!dateText}
                onClass={look.pillOn}
                onPick={(from, to) => apply({ from, to })}
              />
            </div>
          )}
          {choices.map((choice) => (
            <div key={choice.key} className="hidden md:block">
              <ChoicePill
                choice={choice}
                current={value[choice.key] ?? choice.defaultValue}
                text={choiceText(choice) ?? choice.label}
                on={!!value[choice.key] && value[choice.key] !== choice.defaultValue}
                onClass={look.pillOn}
                onPick={(picked) => apply({ [choice.key]: picked })}
              />
            </div>
          ))}
          <button
            type="submit"
            className={cn(
              "flex h-11 flex-none cursor-pointer items-center gap-2 rounded-xl px-3 text-base font-bold md:ml-1 md:h-12 md:px-5",
              look.button,
              look.focus,
            )}
          >
            <Search aria-hidden="true" className="size-4.5" strokeWidth={2.6} />
            <span className="max-md:sr-only">{t("search.button")}</span>
          </button>
          {suggest && (
            <div className="absolute inset-x-0 top-full z-20 mt-2 rounded-2xl bg-popover p-2 text-popover-foreground shadow-[0_18px_40px_rgba(7,57,43,0.28)] md:right-auto md:w-[min(520px,100%)]">
              <p id={`${id}-recent`} className="mx-2 mt-0.5 mb-1 text-[13px] font-bold text-muted-foreground">
                {t("search.recent")}
              </p>
              <ul aria-labelledby={`${id}-recent`}>
                {recent.map((text) => (
                  <li key={text}>
                    <button
                      type="button"
                      data-suggestion
                      className="flex min-h-11 w-full cursor-pointer items-center gap-2.5 rounded-[10px] px-2.5 text-left text-base hover:bg-muted focus-visible:bg-accent focus-visible:outline-none"
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => {
                        setDraft(text);
                        setFocused(false);
                        remember(text);
                        onChange(clean({ ...value, q: text }));
                      }}
                      onKeyDown={(event) => {
                        const target = event.currentTarget.parentElement;
                        const next =
                          event.key === "ArrowDown"
                            ? target?.nextElementSibling
                            : event.key === "ArrowUp"
                              ? target?.previousElementSibling
                              : undefined;
                        if (event.key === "ArrowUp" && !target?.previousElementSibling) {
                          event.preventDefault();
                          input.current?.focus();
                        } else if (next) {
                          event.preventDefault();
                          next.querySelector<HTMLButtonElement>("button")?.focus();
                        }
                      }}
                    >
                      <Clock aria-hidden="true" className="size-4.5 flex-none text-[#6b746e]" />
                      <span className="min-w-0 break-words">{text}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
        {hasFilters && (
          <button
            type="button"
            aria-label={filtersOn ? t("search.filtersOn", { count: format.number(filtersOn) }) : t("search.filters")}
            className={cn(
              "relative flex size-13 flex-none cursor-pointer items-center justify-center self-center rounded-2xl border-[1.5px] md:hidden",
              look.filter,
              look.focus,
            )}
            onClick={() => setSheet(true)}
          >
            <SlidersHorizontal aria-hidden="true" className="size-5.5" />
            {filtersOn > 0 && (
              <span
                aria-hidden="true"
                className={cn(
                  "absolute -top-1.5 -right-1.5 flex h-5.5 min-w-5.5 items-center justify-center rounded-full border-2 px-1 text-xs font-bold",
                  look.badge,
                )}
              >
                {format.number(filtersOn)}
              </span>
            )}
          </button>
        )}
      </form>

      {(chips.length > 0 || count || aside) && (
        <div className="flex items-center gap-2 max-md:-mr-1 max-md:overflow-x-auto max-md:pb-0.5 md:flex-wrap">
          {chips.map((chip) => (
            <button
              key={chip.key}
              type="button"
              aria-label={t("search.remove", { name: chip.text })}
              className={cn(
                "flex min-h-9 flex-none cursor-pointer items-center gap-1.5 rounded-full border py-1 pr-2 pl-3 text-sm font-semibold whitespace-nowrap",
                look.chip,
                look.focus,
              )}
              onClick={chip.remove}
            >
              {chip.text}
              <X aria-hidden="true" className="size-3.5" strokeWidth={2.6} />
            </button>
          ))}
          {chips.length > 0 && (
            <button
              type="button"
              className={cn(
                "flex min-h-9 flex-none cursor-pointer items-center gap-1.5 rounded-full px-3 text-sm font-bold",
                look.reset,
                look.focus,
              )}
              onClick={reset}
            >
              {t("search.reset")}
            </button>
          )}
          {(count || aside) && (
            <div className="ml-auto flex flex-none items-center gap-3">
              {aside}
              {count && (
                <p aria-live="polite" className={cn("text-[15px] whitespace-nowrap", look.sub)}>
                  {count}
                </p>
              )}
            </div>
          )}
        </div>
      )}

      {sheet && (
        <FilterSheet
          value={value}
          dates={dates}
          choices={choices}
          onClose={() => setSheet(false)}
          onApply={(next) => {
            setSheet(false);
            apply(next);
          }}
        />
      )}
    </div>
  );
}

/** Quick picks and two dates, for days to show. */
function DateChoices({
  from,
  to,
  onPreset,
  onFrom,
  onTo,
}: {
  from: string;
  to: string;
  onPreset: (preset: Preset) => void;
  onFrom: (day: string) => void;
  onTo: (day: string) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const picked = presetOf(from, to);
  return (
    <div className="flex flex-col gap-3">
      <div className="grid grid-cols-3 gap-1.5">
        {PRESETS.map((preset) => (
          <button
            key={preset}
            type="button"
            aria-pressed={picked === preset}
            className={cn(CHOICE, picked === preset ? CHOICE_ON : CHOICE_OFF)}
            onClick={() => onPreset(preset)}
          >
            {t(`search.preset.${preset}`)}
          </button>
        ))}
      </div>
      <div className="grid grid-cols-2 gap-2.5">
        <div className="flex min-w-0 flex-col gap-1">
          <Label htmlFor={`${id}-from`} className="text-sm font-semibold text-[#3e4842]">
            {t("search.from")}
          </Label>
          <Input
            id={`${id}-from`}
            type="date"
            max={to || dhakaToday()}
            className="h-11 rounded-[10px] px-2"
            value={from}
            onChange={(event) => onFrom(event.target.value)}
          />
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <Label htmlFor={`${id}-to`} className="text-sm font-semibold text-[#3e4842]">
            {t("search.to")}
          </Label>
          <Input
            id={`${id}-to`}
            type="date"
            min={from || undefined}
            max={dhakaToday()}
            className="h-11 rounded-[10px] px-2"
            value={to}
            onChange={(event) => onTo(event.target.value)}
          />
        </div>
      </div>
    </div>
  );
}

function DatePill({
  value,
  text,
  anyLabel,
  on,
  onClass,
  onPick,
}: {
  value: ListSearchValue;
  text: string;
  anyLabel: string;
  on: boolean;
  onClass: string;
  onPick: (from?: string, to?: string) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setFrom(value.from ?? "");
          setTo(value.to ?? "");
        }
      }}
    >
      <PopoverTrigger asChild>
        <button type="button" aria-label={`${t("search.when")}: ${text}`} className={cn(PILL, on ? onClass : PILL_OFF)}>
          <Calendar aria-hidden="true" className="size-4.5" />
          {text}
          <ChevronDown aria-hidden="true" className="size-4" strokeWidth={2.4} />
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={t("search.when")} className="w-95">
        <p className="mb-2.5 font-bold">{t("search.when")}</p>
        <DateChoices
          from={from}
          to={to}
          onPreset={(preset) => {
            const range = presetRange(preset);
            setOpen(false);
            onPick(range.from, range.to);
          }}
          onFrom={setFrom}
          onTo={setTo}
        />
        <div className="mt-3.5 flex justify-between gap-2 border-t pt-3">
          <button
            type="button"
            className="h-11 cursor-pointer rounded-[10px] px-3 text-[15px] font-bold text-primary hover:bg-muted"
            onClick={() => {
              setOpen(false);
              onPick(undefined, undefined);
            }}
          >
            {anyLabel}
          </button>
          <button
            type="button"
            className="h-11 cursor-pointer rounded-[10px] bg-primary px-4.5 text-[15px] font-bold text-primary-foreground hover:bg-[#0a5c44]"
            onClick={() => {
              setOpen(false);
              onPick(from || undefined, to || undefined);
            }}
          >
            {t("search.apply")}
          </button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function ChoicePill({
  choice,
  current,
  text,
  on,
  onClass,
  onPick,
}: {
  choice: ChoiceFilter;
  current: string | undefined;
  text: string;
  on: boolean;
  onClass: string;
  onPick: (value: string | undefined) => void;
}) {
  const [open, setOpen] = useState(false);
  const Icon = choice.icon;
  const options = [...(choice.allLabel ? [{ value: "", label: choice.allLabel }] : []), ...choice.options];
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" aria-label={`${choice.label}: ${text}`} className={cn(PILL, on ? onClass : PILL_OFF)}>
          <Icon aria-hidden="true" className="size-4.5" />
          {text}
          <ChevronDown aria-hidden="true" className="size-4" strokeWidth={2.4} />
        </button>
      </PopoverTrigger>
      <PopoverContent aria-label={choice.label} className="max-h-96 w-75 overflow-y-auto p-2">
        <p className="mx-2 mt-1 mb-1.5 font-bold">{choice.label}</p>
        <ul>
          {options.map((option) => {
            const picked = (current ?? "") === option.value;
            return (
              <li key={option.value}>
                <button
                  type="button"
                  aria-pressed={picked}
                  className={cn(
                    "flex min-h-11 w-full cursor-pointer items-center justify-between gap-2.5 rounded-[10px] px-2.5 text-left text-[15px] hover:bg-muted",
                    picked && "bg-accent font-bold",
                  )}
                  onClick={() => {
                    setOpen(false);
                    onPick(option.value || undefined);
                  }}
                >
                  <span className="min-w-0 break-words">{option.label}</span>
                  {picked && <span aria-hidden="true" className="size-2.5 flex-none rounded-full bg-primary" />}
                </button>
              </li>
            );
          })}
        </ul>
      </PopoverContent>
    </Popover>
  );
}

/** On phones: every filter at once, applied together. */
function FilterSheet({
  value,
  dates,
  choices,
  onClose,
  onApply,
}: {
  value: ListSearchValue;
  dates?: DateFilter;
  choices: ChoiceFilter[];
  onClose: () => void;
  onApply: (value: ListSearchValue) => void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<ListSearchValue>(() => ({
    from: value.from ?? "",
    to: value.to ?? "",
    ...Object.fromEntries(choices.map((choice) => [choice.key, value[choice.key] ?? ""])),
  }));
  const set = (next: ListSearchValue) => setDraft((current) => ({ ...current, ...next }));
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="top-auto bottom-0 left-0 flex max-h-[88dvh] max-w-full translate-x-0 translate-y-0 flex-col gap-0 rounded-none rounded-t-3xl p-0 text-base sm:max-w-full"
      >
        <div aria-hidden="true" className="mx-auto mt-2 h-1.25 w-11 flex-none rounded-full bg-input" />
        <div className="flex flex-none items-center justify-between py-1 pr-2 pl-4.5">
          <DialogTitle className="text-[21px] font-bold">{t("search.filters")}</DialogTitle>
          <button
            type="button"
            aria-label={t("search.close")}
            className="flex size-11 cursor-pointer items-center justify-center rounded-[10px] hover:bg-muted"
            onClick={onClose}
          >
            <X aria-hidden="true" className="size-5.5" />
          </button>
        </div>
        <DialogDescription className="sr-only">{t("search.filters")}</DialogDescription>
        <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4.5 pt-1.5 pb-4">
          {dates && (
            <fieldset className="min-w-0">
              <legend className="mb-2 font-bold">{t("search.when")}</legend>
              <DateChoices
                from={draft.from ?? ""}
                to={draft.to ?? ""}
                onPreset={(preset) => {
                  const range = presetRange(preset);
                  const again = presetOf(draft.from, draft.to) === preset;
                  set(again ? { from: "", to: "" } : range);
                }}
                onFrom={(from) => set({ from })}
                onTo={(to) => set({ to })}
              />
            </fieldset>
          )}
          {choices.map((choice) => {
            const options = [...(choice.allLabel ? [{ value: "", label: choice.allLabel }] : []), ...choice.options];
            const current = draft[choice.key] || (choice.allLabel ? "" : choice.defaultValue);
            return (
              <fieldset key={choice.key} className="min-w-0">
                <legend className="mb-2 font-bold">{choice.label}</legend>
                <div className="flex flex-wrap gap-1.5">
                  {options.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={current === option.value}
                      className={cn(CHOICE, "rounded-full px-3.5", current === option.value ? CHOICE_ON : CHOICE_OFF)}
                      onClick={() => set({ [choice.key]: option.value })}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </fieldset>
            );
          })}
        </div>
        <div className="grid flex-none grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2.5 border-t bg-background px-4.5 pt-3 pb-4.5">
          <button
            type="button"
            className="h-13 cursor-pointer rounded-xl border-[1.5px] border-input bg-card text-base font-bold hover:bg-muted"
            onClick={() =>
              setDraft({ from: "", to: "", ...Object.fromEntries(choices.map((choice) => [choice.key, ""])) })
            }
          >
            {t("search.reset")}
          </button>
          <button
            type="button"
            className="h-13 cursor-pointer rounded-xl bg-primary text-[17px] font-bold text-primary-foreground hover:bg-[#0a5c44]"
            onClick={() => onApply(clean(draft))}
          >
            {t("search.showResults")}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
