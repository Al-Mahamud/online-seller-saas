import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Check, ChevronLeft, Minus, Plus, Search, X } from "lucide-react";
import { type ReactNode, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useBusiness } from "@/api/business";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorText } from "@/api/query";
import type { components } from "@/api/schema";
import { LoadMore } from "@/components/layout/LoadMore";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import { containsText } from "@/lib/search";
import { cn } from "@/lib/utils";
import { lowStockAllQuery, stockChanged } from "./api";

type Item = components["schemas"]["LowStockItem"];
type Tab = "all" | "out" | "low";
type View = { kind: "list" } | { kind: "receive"; ids: string[] } | { kind: "shopping"; ids: string[] };

const MAX_QUANTITY = 1_000_000;
const QUICK = [5, 10, 20];
const BUTTON = "h-11.5 cursor-pointer rounded-xl px-4 text-[15px] font-bold disabled:cursor-default";
const OUTLINE =
  "border-[1.5px] border-primary bg-card text-primary hover:bg-accent disabled:border-border disabled:text-muted-foreground";
const SOLID = "bg-primary text-primary-foreground hover:bg-[#0a5c44] disabled:bg-[#9AA19C]";
const STEP =
  "flex size-11 flex-none cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card hover:bg-muted";

const nameOf = (item: Item) => (item.label ? `${item.productName}, ${item.label}` : item.productName);
/** Enough to reach twice the alert level: a first guess at how many to buy or that came. */
const restock = (item: Item) => Math.max(1, item.threshold * 2 - item.available);

/**
 * What's running low, in a window over whatever page is open: emptiest first, with stock that
 * arrived written in place and a shopping list to send a supplier. Every member can do both.
 */
export default function LowStockDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const query = useInfiniteQuery(lowStockAllQuery);
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const total = query.data?.pages[0]?.totalItems ?? 0;
  const [view, setView] = useState<View>({ kind: "list" });
  const [picked, setPicked] = useState<string[]>([]);
  const byId = new Map(items.map((item) => [item.variantId, item]));
  const chosen = (ids: string[]) => ids.flatMap((id) => byId.get(id) ?? []);
  const count = (value: number) => t("lowStock.count", { count: format.number(value) });
  const out = items.filter((item) => item.available <= 0).length;

  const header =
    view.kind === "list"
      ? {
          title: t("lowStock.title"),
          subtitle: t("lowStock.subtitle"),
          figures: [
            { label: t("lowStock.out"), value: count(out), className: "text-[#F7A99F]" },
            { label: t("lowStock.low"), value: count(total - out), className: "text-marigold-text" },
          ],
        }
      : view.kind === "receive"
        ? { title: t("lowStock.arrived"), subtitle: t("lowStock.receive.subtitle"), figures: [] }
        : { title: t("lowStock.shoppingList"), subtitle: t("lowStock.shopping.subtitle"), figures: [] };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="flex max-h-[calc(100dvh-2rem)] flex-col gap-0 overflow-hidden p-0 text-base max-md:h-dvh max-md:max-h-dvh max-md:max-w-full max-md:rounded-none sm:max-w-[760px]"
      >
        <header className="relative flex flex-none flex-col gap-3 bg-ledger py-3 pr-2.5 pl-6.5 text-white md:py-4 md:pr-4 md:pl-11">
          <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-5.5" />
          <div className="flex items-start justify-between gap-3">
            <div className="flex min-w-0 items-center gap-1">
              {view.kind !== "list" && (
                <button
                  type="button"
                  aria-label={t("lowStock.back")}
                  className="-ml-2 flex size-10 flex-none cursor-pointer items-center justify-center rounded-[10px] hover:bg-white/10"
                  onClick={() => setView({ kind: "list" })}
                >
                  <ChevronLeft aria-hidden="true" className="size-5.5" />
                </button>
              )}
              <div className="min-w-0">
                <DialogTitle className="text-2xl leading-snug font-bold">{header.title}</DialogTitle>
                <DialogDescription className="text-[15px] text-ledger-text">{header.subtitle}</DialogDescription>
              </div>
            </div>
            <button
              type="button"
              aria-label={t("page.close")}
              className="flex size-11 flex-none cursor-pointer items-center justify-center rounded-[10px] hover:bg-white/10"
              onClick={onClose}
            >
              <X aria-hidden="true" className="size-5.5" />
            </button>
          </div>
          {view.kind === "list" && query.isSuccess && total > 0 && (
            <dl className="flex flex-wrap gap-x-7 gap-y-1.5 border-t border-ledger-outline pt-2.5">
              {header.figures.map((figure) => (
                <div key={figure.label}>
                  <dt className="text-sm font-semibold text-ledger-text">{figure.label}</dt>
                  <dd className={cn("text-2xl leading-tight font-bold", figure.className)}>{figure.value}</dd>
                </div>
              ))}
            </dl>
          )}
        </header>

        {view.kind === "list" && (
          <List
            items={items}
            loaded={query.isSuccess}
            more={<LoadMore query={query} />}
            picked={picked}
            onPick={setPicked}
            onReceive={(ids) => setView({ kind: "receive", ids })}
            onShop={(ids) => setView({ kind: "shopping", ids })}
          />
        )}
        {view.kind === "receive" && (
          <Receive
            items={chosen(view.ids)}
            onBack={() => setView({ kind: "list" })}
            onDone={(ids) => {
              setPicked((current) => current.filter((id) => !ids.includes(id)));
              setView({ kind: "list" });
            }}
          />
        )}
        {view.kind === "shopping" && (
          <Shopping items={chosen(view.ids.length ? view.ids : items.map((item) => item.variantId))} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function List({
  items,
  loaded,
  more,
  picked,
  onPick,
  onReceive,
  onShop,
}: {
  items: Item[];
  loaded: boolean;
  more: ReactNode;
  picked: string[];
  onPick: (ids: string[]) => void;
  onReceive: (ids: string[]) => void;
  onShop: (ids: string[]) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [q, setQ] = useState("");
  const [tab, setTab] = useState<Tab>("all");
  const text = q.trim();
  const matching = items.filter(
    (item) =>
      !text || containsText(item.productName, text) || containsText(item.label, text) || containsText(item.sku, text),
  );
  const counts = {
    all: matching.length,
    out: matching.filter((item) => item.available <= 0).length,
    low: matching.filter((item) => item.available > 0).length,
  };
  const shown = matching.filter((item) => tab === "all" || (tab === "out") === item.available <= 0);
  const ids = shown.map((item) => item.variantId);
  const allOn = ids.length > 0 && ids.every((id) => picked.includes(id));
  const count = (value: number) => t("lowStock.count", { count: format.number(value) });

  if (loaded && items.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2.5 px-5 py-9 text-center">
        <span aria-hidden="true" className="flex size-14 items-center justify-center rounded-full bg-accent">
          <Check className="size-7 text-primary" strokeWidth={2.4} />
        </span>
        <p className="text-lg font-bold">{t("lowStock.none")}</p>
        <p className="max-w-sm text-[15px] text-muted-foreground">{t("lowStock.noneHint")}</p>
        <Link to="/products" className={cn(BUTTON, OUTLINE, "flex items-center no-underline")}>
          {t("lowStock.allProducts")}
        </Link>
      </div>
    );
  }
  return (
    <>
      <div className="flex flex-none flex-wrap items-center gap-2.5 border-b px-3.5 py-2.5 md:px-5 md:py-3">
        <div className="relative min-w-0 flex-[1_1_220px]">
          <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-primary" />
          <Input
            type="search"
            aria-label={t("lowStock.search")}
            placeholder={t("lowStock.searchHint")}
            className="h-11.5 rounded-xl pl-9.5 text-base"
            value={q}
            onChange={(event) => setQ(event.target.value)}
          />
        </div>
        {/* biome-ignore lint/a11y/useSemanticElements: three buttons that belong together, with no form around them. */}
        <div role="group" aria-label={t("lowStock.which")} className="flex gap-1.5">
          {(["all", "out", "low"] as const).map((key) => (
            <button
              key={key}
              type="button"
              aria-pressed={tab === key}
              className={cn(
                "flex h-11 flex-none cursor-pointer items-center gap-1.5 rounded-[10px] border-[1.5px] px-3 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring",
                tab === key
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-input bg-card hover:bg-muted",
              )}
              onClick={() => setTab(key)}
            >
              {t(`lowStock.tab.${key}`)}
              <span
                className={cn(
                  "min-w-5 rounded-full px-1.5 text-[13px] font-bold",
                  tab === key ? "bg-white/20" : "bg-muted",
                )}
              >
                {format.number(counts[key])}
              </span>
            </button>
          ))}
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {shown.length > 0 && (
          <Label className="flex min-h-11 cursor-pointer items-center gap-2.5 border-b bg-background px-3.5 text-sm font-semibold text-[#3E4842] md:px-5">
            <input
              type="checkbox"
              checked={allOn}
              className="size-4.5 accent-primary"
              onChange={() =>
                onPick(allOn ? picked.filter((id) => !ids.includes(id)) : [...new Set([...picked, ...ids])])
              }
            />
            {t("lowStock.selectAll")}
          </Label>
        )}
        <ul>
          {shown.map((item) => {
            const on = picked.includes(item.variantId);
            const empty = item.available <= 0;
            return (
              <li
                key={item.variantId}
                className={cn("flex items-center gap-3 border-b px-3.5 py-2.5 md:px-5", on && "bg-[#F3F9F6]")}
              >
                <input
                  type="checkbox"
                  aria-label={t("lowStock.pick", { name: nameOf(item) })}
                  checked={on}
                  className="size-4.5 flex-none accent-primary"
                  onChange={() =>
                    onPick(on ? picked.filter((id) => id !== item.variantId) : [...picked, item.variantId])
                  }
                />
                <div className="flex min-w-0 flex-1 flex-col gap-1.25">
                  <div className="flex items-baseline justify-between gap-2.5">
                    <Link
                      to="/products/$productId"
                      params={{ productId: item.productId }}
                      className="min-w-0 text-foreground no-underline hover:underline"
                    >
                      <span className="font-bold">{item.productName}</span>
                      {item.label && <span className="text-sm text-muted-foreground"> {item.label}</span>}
                    </Link>
                    <span
                      className={cn(
                        "flex-none rounded-full px-2.25 text-[13px] font-bold",
                        empty ? "bg-danger-soft text-danger-ink" : "bg-warning text-warning-foreground",
                      )}
                    >
                      {t(empty ? "lowStock.badge.out" : "lowStock.badge.low")}
                    </span>
                  </div>
                  <div aria-hidden="true" className="h-1.5 rounded-sm bg-muted">
                    <div
                      className={cn("h-1.5 rounded-sm", empty ? "bg-[#C2553D]" : "bg-[#C4820F]")}
                      style={{
                        width: empty
                          ? 0
                          : `${Math.max(6, Math.min(100, (item.available / (item.threshold * 2)) * 100))}%`,
                      }}
                    />
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-[#3E4842]">
                    <span>
                      {t("lowStock.line", {
                        available: count(Math.max(0, item.available)),
                        threshold: count(item.threshold),
                        sku: item.sku,
                      })}
                    </span>
                    <button
                      type="button"
                      aria-label={t("lowStock.arrivedFor", { name: nameOf(item) })}
                      className="flex h-9 flex-none cursor-pointer items-center gap-1 rounded-[10px] border-[1.5px] border-primary bg-card px-3 text-sm font-bold text-primary hover:bg-accent"
                      onClick={() => onReceive([item.variantId])}
                    >
                      <Plus aria-hidden="true" className="size-4" strokeWidth={2.6} />
                      {t("lowStock.arrived")}
                    </button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
        {loaded && items.length > 0 && shown.length === 0 && (
          <p className="px-4 py-7 text-center font-bold">{t("lowStock.noMatch")}</p>
        )}
        <div className="px-4">{more}</div>
      </div>
      {items.length > 0 && (
        <footer className="flex flex-none flex-wrap items-center justify-between gap-2.5 border-t bg-background px-3.5 pt-3 pb-3.5 md:px-5">
          <p role="status" className="text-[15px] font-semibold text-[#3E4842]">
            {picked.length
              ? t("lowStock.selected", { count: format.number(picked.length) })
              : t("lowStock.noneSelected")}
          </p>
          <div className="flex gap-2 max-md:w-full">
            <button
              type="button"
              disabled={!picked.length}
              className={cn(BUTTON, OUTLINE, "max-md:flex-1")}
              onClick={() => onShop(picked)}
            >
              {t("lowStock.shoppingList")}
            </button>
            <button
              type="button"
              disabled={!picked.length}
              className={cn(BUTTON, SOLID, "max-md:flex-1")}
              onClick={() => onReceive(picked)}
            >
              {t("lowStock.arrived")}
            </button>
          </div>
        </footer>
      )}
    </>
  );
}

function Receive({ items, onBack, onDone }: { items: Item[]; onBack: () => void; onDone: (ids: string[]) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const queryClient = useQueryClient();
  const [texts, setTexts] = useState<Record<string, string>>(() =>
    Object.fromEntries(items.map((item) => [item.variantId, String(restock(item))])),
  );
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string>();
  // Kept for retries of this one change, so a double tap or a bad connection never adds twice.
  const idempotencyKey = useRef(newIdempotencyKey());
  const quantity = (item: Item) => parseCount(texts[item.variantId] ?? "");
  const bad = items.some((item) => {
    const value = quantity(item);
    return value === null || value > MAX_QUANTITY;
  });
  const lines = items.flatMap((item) => {
    const value = quantity(item);
    return value ? [{ variantId: item.variantId, kind: "RECEIVE" as const, quantity: value }] : [];
  });
  const total = lines.reduce((sum, line) => sum + line.quantity, 0);
  const save = useMutation({
    mutationFn: () =>
      call(
        api.POST("/api/v1/stock/adjustments", {
          body: { lines, note: note.trim() || undefined },
          headers: { "Idempotency-Key": idempotencyKey.current },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      stockChanged(queryClient);
      toast.success(t("lowStock.receive.done"));
      onDone(items.map((item) => item.variantId));
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        idempotencyKey.current = newIdempotencyKey();
      }
      setProblem(errorText(error));
    },
  });
  const set = (item: Item, value: number) =>
    setTexts((current) => ({ ...current, [item.variantId]: String(Math.max(0, value)) }));
  return (
    <form
      noValidate
      className="flex min-h-0 flex-1 flex-col"
      onSubmit={(event) => {
        event.preventDefault();
        if (bad) {
          setProblem(t("lowStock.receive.badNumber"));
        } else if (lines.length === 0) {
          setProblem(t("lowStock.receive.nothing"));
        } else {
          setProblem(undefined);
          save.mutate();
        }
      }}
    >
      <div className="flex min-h-0 flex-1 flex-col gap-3.5 overflow-y-auto px-3.5 py-3.5 md:px-5">
        {problem && (
          <p
            role="alert"
            className="rounded-[10px] bg-danger-soft px-3.5 py-2.75 text-[15px] font-semibold text-danger-ink"
          >
            {problem}
          </p>
        )}
        <ul className="rounded-xl border">
          {items.map((item, index) => {
            const value = quantity(item);
            const name = nameOf(item);
            return (
              <li key={item.variantId} className={cn("flex flex-col gap-2 p-3", index > 0 && "border-t")}>
                <div className="flex flex-wrap items-center gap-2.5">
                  <div className="min-w-0 flex-[1_1_180px]">
                    <Label htmlFor={`${id}-${item.variantId}`} className="block font-bold break-words">
                      {name}
                    </Label>
                    <p className="text-sm text-muted-foreground">
                      {t("lowStock.receive.now", {
                        now: t("lowStock.count", { count: format.number(Math.max(0, item.available)) }),
                        threshold: t("lowStock.count", { count: format.number(item.threshold) }),
                      })}
                    </p>
                  </div>
                  <div className="flex flex-none items-center gap-1">
                    <button
                      type="button"
                      aria-label={t("lowStock.receive.less")}
                      className={STEP}
                      onClick={() => set(item, (value ?? 0) - 1)}
                    >
                      <Minus aria-hidden="true" className="size-4.5" />
                    </button>
                    <Input
                      id={`${id}-${item.variantId}`}
                      inputMode="numeric"
                      aria-invalid={value === null ? true : undefined}
                      className="h-11 w-15 text-center text-[17px] font-bold"
                      value={texts[item.variantId] ?? ""}
                      onChange={(event) =>
                        setTexts((current) => ({ ...current, [item.variantId]: event.target.value }))
                      }
                    />
                    <button
                      type="button"
                      aria-label={t("lowStock.receive.more")}
                      className={STEP}
                      onClick={() => set(item, (value ?? 0) + 1)}
                    >
                      <Plus aria-hidden="true" className="size-4.5" />
                    </button>
                  </div>
                </div>
                <div className="flex flex-wrap items-center justify-between gap-1.5">
                  <div className="flex gap-1.5">
                    {QUICK.map((n) => (
                      <button
                        key={n}
                        type="button"
                        className="min-h-9 cursor-pointer rounded-full border border-mint-line bg-accent px-3 text-sm font-bold text-accent-foreground hover:bg-[#d5e9df]"
                        onClick={() => set(item, n)}
                      >
                        {t("lowStock.count", { count: format.number(n) })}
                      </button>
                    ))}
                  </div>
                  {value !== null && (
                    <span className="text-sm font-semibold text-primary">
                      {t("lowStock.receive.after", { count: format.number(Math.max(0, item.available) + value) })}
                    </span>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-note`} className="text-[15px] font-semibold">
            {t("lowStock.receive.note")}
          </Label>
          <Input
            id={`${id}-note`}
            maxLength={500}
            placeholder={t("lowStock.receive.noteHint")}
            className="h-11.5 rounded-[10px]"
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
      </div>
      <footer className="flex flex-none justify-end gap-2 border-t bg-background px-3.5 pt-3 pb-3.5 md:px-5">
        <button
          type="button"
          className={cn(BUTTON, "border-[1.5px] border-input bg-card hover:bg-muted max-md:flex-1")}
          onClick={onBack}
        >
          {t("lowStock.backButton")}
        </button>
        <button type="submit" disabled={save.isPending} className={cn(BUTTON, SOLID, "max-md:flex-[2]")}>
          {total > 0 ? t("lowStock.receive.save", { count: format.number(total) }) : t("lowStock.receive.saveEmpty")}
        </button>
      </footer>
    </form>
  );
}

function Shopping({ items }: { items: Item[] }) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const [buy, setBuy] = useState<Record<string, number>>(() =>
    Object.fromEntries(items.map((item) => [item.variantId, restock(item)])),
  );
  const [copied, setCopied] = useState(false);
  const count = (value: number) => t("lowStock.count", { count: format.number(value) });
  const text = [
    t("lowStock.shopping.heading", { business: business.name, date: format.dayMonth(dhakaToday()) }),
    ...items
      .filter((item) => (buy[item.variantId] ?? 0) > 0)
      .map((item) =>
        t("lowStock.shopping.line", {
          name: nameOf(item),
          sku: item.sku,
          count: format.number(buy[item.variantId] ?? 0),
        }),
      ),
  ].join("\n");
  const set = (item: Item, value: number) => {
    setBuy((current) => ({ ...current, [item.variantId]: Math.max(0, value) }));
    setCopied(false);
  };
  return (
    <>
      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-3.5 py-3.5 md:px-5">
        <ul className="rounded-xl border">
          {items.map((item, index) => (
            <li key={item.variantId} className={cn("flex items-center gap-2 px-3 py-2", index > 0 && "border-t")}>
              <div className="min-w-0 flex-1">
                <p className="text-[15px] font-bold break-words">{nameOf(item)}</p>
                <p className="text-[13px] text-muted-foreground">
                  {t("lowStock.shopping.now", { sku: item.sku, now: count(Math.max(0, item.available)) })}
                </p>
              </div>
              <button
                type="button"
                aria-label={`${t("lowStock.receive.less")}: ${nameOf(item)}`}
                className={STEP}
                onClick={() => set(item, (buy[item.variantId] ?? 0) - 1)}
              >
                <Minus aria-hidden="true" className="size-4.5" />
              </button>
              <span aria-live="polite" className="min-w-11 text-center font-bold">
                {count(buy[item.variantId] ?? 0)}
              </span>
              <button
                type="button"
                aria-label={`${t("lowStock.receive.more")}: ${nameOf(item)}`}
                className={STEP}
                onClick={() => set(item, (buy[item.variantId] ?? 0) + 1)}
              >
                <Plus aria-hidden="true" className="size-4.5" />
              </button>
            </li>
          ))}
        </ul>
        <div>
          <p className="mb-1.5 text-[15px] font-semibold">{t("lowStock.shopping.preview")}</p>
          <pre className="rounded-xl bg-background px-3.5 py-3 font-sans text-[15px] leading-relaxed whitespace-pre-wrap">
            {text}
          </pre>
        </div>
        <p className="text-[13px] text-muted-foreground">{t("lowStock.shopping.hint")}</p>
      </div>
      <footer className="flex flex-none flex-wrap justify-end gap-2 border-t bg-background px-3.5 pt-3 pb-3.5 md:px-5">
        <button
          type="button"
          className={cn(BUTTON, OUTLINE, "max-md:flex-1")}
          onClick={() => {
            void navigator.clipboard?.writeText(text).then(() => setCopied(true));
          }}
        >
          {copied ? t("lowStock.shopping.copied") : t("lowStock.shopping.copy")}
        </button>
        <a
          href={`https://wa.me/?text=${encodeURIComponent(text)}`}
          target="_blank"
          rel="noopener noreferrer"
          className={cn(
            BUTTON,
            SOLID,
            "flex items-center justify-center no-underline hover:text-primary-foreground max-md:flex-1",
          )}
        >
          {t("lowStock.shopping.whatsapp")}
        </a>
      </footer>
    </>
  );
}
