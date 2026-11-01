import { useMutation, useQueryClient } from "@tanstack/react-query";
import { X } from "lucide-react";
import { useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorText } from "@/api/query";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Product, Variant } from "./api";

type Kind = "RECEIVE" | "DAMAGE" | "COUNT";
const KINDS: Kind[] = ["RECEIVE", "DAMAGE", "COUNT"];
const SIGNS: Record<Kind, string> = { RECEIVE: "+", DAMAGE: "−", COUNT: "=" };
const MAX_QUANTITY = 1_000_000;

/** What a box says for this variant: nothing, a problem, or the stock it would leave. The server checks again. */
type Line =
  | { state: "empty" }
  | { state: "bad"; problem: "number" | "damage" | "count" }
  | { state: "ok"; quantity: number; after: number };

function readLine(text: string | undefined, kind: Kind, variant: Variant): Line {
  const typed = text?.trim();
  if (!typed) {
    return { state: "empty" };
  }
  const quantity = parseCount(typed);
  if (quantity === null || quantity > MAX_QUANTITY || (kind !== "COUNT" && quantity === 0)) {
    return { state: "bad", problem: "number" };
  }
  if (kind === "DAMAGE" && quantity > variant.available) {
    return { state: "bad", problem: "damage" };
  }
  if (kind === "COUNT" && quantity < variant.reserved) {
    return { state: "bad", problem: "count" };
  }
  const after =
    kind === "RECEIVE" ? variant.onHand + quantity : kind === "DAMAGE" ? variant.onHand - quantity : quantity;
  return { state: "ok", quantity, after };
}

/**
 * A delivery, damage or a shelf count, for any of the product's variants at once: all of it happens
 * or none. Empty boxes are left alone. Each row shows what it will leave, before saving.
 */
export function StockDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<Kind>("RECEIVE");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string>();
  // Kept for retries of this one change, so a double tap or a bad connection never makes two.
  const idempotencyKey = useRef(newIdempotencyKey());
  const variants = product.variants.filter((variant) => variant.status === "ACTIVE");
  const rows = variants.map((variant) => ({ variant, line: readLine(quantities[variant.id], kind, variant) }));
  const changes = rows.flatMap(({ variant, line }) => (line.state === "ok" ? [{ variant, ...line }] : []));
  const total = changes.reduce((sum, change) => sum + (change.after - change.variant.onHand), 0);

  const adjust = useMutation({
    mutationFn: (lines: { variantId: string; kind: Kind; quantity: number }[]) =>
      call(
        api.POST("/api/v1/stock/adjustments", {
          body: { lines, note: note.trim() || undefined },
          headers: { "Idempotency-Key": idempotencyKey.current },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["product", product.id] });
      void queryClient.invalidateQueries({ queryKey: ["products"] });
      void queryClient.invalidateQueries({ queryKey: ["low-stock"] });
      void queryClient.invalidateQueries({ queryKey: ["movements"] });
      toast.success(t("stock.done"));
      onClose();
    },
    onError: (error) => {
      // The server answered: this attempt is finished, so a new one is a new change.
      if (error instanceof ApiError) {
        idempotencyKey.current = newIdempotencyKey();
        // Someone may have sold or moved stock meanwhile: the rows take the latest.
        void queryClient.invalidateQueries({ queryKey: ["product", product.id] });
      }
      setProblem(errorText(error));
    },
  });

  function submit() {
    // A row with a problem says so under itself.
    if (rows.some(({ line }) => line.state === "bad")) {
      setProblem(undefined);
      return;
    }
    if (changes.length === 0) {
      setProblem(t("stock.nothing"));
      return;
    }
    setProblem(undefined);
    adjust.mutate(changes.map((change) => ({ variantId: change.variant.id, kind, quantity: change.quantity })));
  }

  const signedTotal = `${total > 0 ? "+" : total < 0 ? "−" : ""}${format.number(Math.abs(total))}`;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="flex max-h-[calc(100dvh-2rem)] flex-col gap-0 overflow-hidden p-0 text-base max-md:h-dvh max-md:max-h-dvh max-md:max-w-full max-md:rounded-none sm:max-w-[680px]"
      >
        <header className="relative flex flex-none items-start justify-between gap-3 bg-ledger py-3 pr-2.5 pl-6.5 text-white md:py-4 md:pr-4 md:pl-12">
          <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-6" />
          <div className="min-w-0">
            <DialogTitle className="text-2xl leading-snug font-bold">{t("stock.change")}</DialogTitle>
            <DialogDescription className="text-[15px] break-words text-ledger-text">{product.name}</DialogDescription>
          </div>
          <DialogClose asChild>
            <Button
              variant="ghost"
              size="icon"
              className="size-11 flex-none text-white hover:bg-white/10 hover:text-white focus-visible:ring-marigold-text"
            >
              <X aria-hidden="true" className="size-5" />
              <span className="sr-only">{t("page.close")}</span>
            </Button>
          </DialogClose>
        </header>
        <form
          noValidate
          className="flex min-h-0 flex-1 flex-col"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4 md:px-6 md:py-4.5">
            {problem && (
              <p
                role="alert"
                className="rounded-[10px] bg-danger-soft px-3.5 py-2.75 text-[15px] font-semibold text-danger-ink"
              >
                {problem}
              </p>
            )}
            <fieldset className="min-w-0">
              <legend className="mb-2 text-[15px] font-semibold">{t("stock.what")}</legend>
              <div className="grid gap-2 md:grid-cols-3 md:gap-2.5">
                {KINDS.map((option) => (
                  <Label
                    key={option}
                    className={cn(
                      "relative flex min-h-13.5 cursor-pointer items-center gap-2.5 rounded-xl border-[1.5px] px-3 py-1.5 text-base leading-snug font-semibold has-focus-visible:ring-3 has-focus-visible:ring-ring/50 md:min-h-15 md:text-[15px]",
                      kind === option ? "border-primary bg-accent" : "border-input bg-card",
                    )}
                  >
                    <input
                      type="radio"
                      name={`${id}-kind`}
                      className="absolute size-px opacity-0"
                      checked={kind === option}
                      onChange={() => setKind(option)}
                    />
                    <span
                      aria-hidden="true"
                      className={cn(
                        "flex size-8.5 flex-none items-center justify-center rounded-[9px] text-xl leading-none font-bold",
                        kind === option ? "bg-primary text-primary-foreground" : "bg-muted text-[#2B342F]",
                      )}
                    >
                      {SIGNS[option]}
                    </span>
                    {t(`stock.kind.${option}`)}
                  </Label>
                ))}
              </div>
              <p className="mt-2 text-sm text-muted-foreground">{t(`stock.hint.${kind}`)}</p>
            </fieldset>

            <div>
              <div
                aria-hidden="true"
                className="hidden grid-cols-[minmax(0,1fr)_130px_92px] gap-3 border-b pb-1.5 text-[13px] font-semibold text-muted-foreground md:grid"
              >
                <span>{t("products.variants")}</span>
                <span>{t(`stock.amount.${kind}`)}</span>
                <span className="text-right">{t("stock.afterColumn")}</span>
              </div>
              {rows.map(({ variant, line }) => {
                const noteId = `${id}-${variant.id}-now`;
                const problemId = `${id}-${variant.id}-problem`;
                const wrong = line.state === "bad";
                return (
                  <div key={variant.id} className="border-t py-2.5 md:border-t-0 md:border-b md:py-2">
                    <div className="grid grid-cols-[minmax(0,1fr)_96px] items-center gap-x-2.5 gap-y-1.5 md:grid-cols-[minmax(0,1fr)_130px_92px] md:gap-x-3">
                      <div className="col-span-2 flex flex-wrap items-baseline justify-between gap-x-2.5 md:col-span-1 md:block">
                        <Label
                          htmlFor={`${id}-${variant.id}`}
                          className="text-base leading-normal font-bold md:font-semibold"
                        >
                          {variant.label || product.name}
                        </Label>
                        <p id={noteId} className="text-sm text-muted-foreground">
                          {variant.reserved > 0
                            ? t("stock.nowReserved", {
                                count: format.number(variant.onHand),
                                reserved: format.number(variant.reserved),
                              })
                            : t("stock.now", { count: format.number(variant.onHand) })}
                        </p>
                      </div>
                      <div className="relative">
                        <span
                          aria-hidden="true"
                          className="absolute top-1/2 left-3 -translate-y-1/2 text-[17px] font-bold text-muted-foreground"
                        >
                          {SIGNS[kind]}
                        </span>
                        <Input
                          id={`${id}-${variant.id}`}
                          inputMode="numeric"
                          aria-invalid={wrong || undefined}
                          aria-describedby={wrong ? `${noteId} ${problemId}` : noteId}
                          className="h-12 rounded-[10px] border-[1.5px] pr-3 pl-7.5 text-base md:h-11"
                          value={quantities[variant.id] ?? ""}
                          onChange={(event) => {
                            setProblem(undefined);
                            setQuantities({ ...quantities, [variant.id]: event.target.value });
                          }}
                        />
                      </div>
                      <p className="text-right">
                        <span className="block text-[13px] text-muted-foreground md:sr-only">
                          {t("stock.afterColumn")}
                        </span>
                        <span
                          className={cn(
                            "block text-lg leading-tight font-bold",
                            line.state === "ok" ? "text-primary" : "text-muted-foreground",
                          )}
                        >
                          {line.state === "ok" ? format.number(line.after) : "–"}
                        </span>
                      </p>
                    </div>
                    {wrong && (
                      <p
                        id={problemId}
                        className="mt-1.5 flex items-start gap-1.5 text-sm font-semibold text-destructive"
                      >
                        <X aria-hidden="true" className="mt-0.5 size-4 flex-none" strokeWidth={2.5} />
                        {line.problem === "number"
                          ? t("stock.badNumber")
                          : line.problem === "damage"
                            ? t("stock.tooManyGone", { count: format.number(variant.available) })
                            : t("stock.belowReserved", { count: format.number(variant.reserved) })}
                      </p>
                    )}
                  </div>
                );
              })}
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-note`} className="text-[15px] leading-normal font-semibold">
                {t("stock.note")}
              </Label>
              <Input
                id={`${id}-note`}
                value={note}
                maxLength={500}
                placeholder={t("stock.noteHint")}
                className="h-12 rounded-[10px] border-[1.5px] text-base md:h-11"
                onChange={(event) => setNote(event.target.value)}
              />
            </div>
          </div>

          <footer className="flex flex-none flex-col gap-2.5 border-t bg-background px-4 pt-3 pb-4 md:flex-row md:flex-wrap md:items-center md:justify-between md:gap-x-4 md:px-6 md:py-3.5">
            <p role="status" className="text-[15px] font-semibold text-[#2B342F]">
              {changes.length === 0
                ? t("stock.noChange")
                : t("stock.summary", {
                    count: changes.length,
                    variants: format.number(changes.length),
                    change: signedTotal,
                  })}
            </p>
            <div className="flex gap-2.5">
              <DialogClose asChild>
                <Button
                  variant="outline"
                  className="h-11.5 rounded-[10px] border-[1.5px] text-base font-semibold max-md:hidden"
                >
                  {t("products.variantRows.cancel")}
                </Button>
              </DialogClose>
              <Button
                type="submit"
                className="h-13 flex-1 rounded-[10px] text-lg font-semibold md:h-11.5 md:flex-none md:px-5.5 md:text-base"
                disabled={adjust.isPending}
              >
                {t("stock.save")}
              </Button>
            </div>
          </footer>
        </form>
      </DialogContent>
    </Dialog>
  );
}
