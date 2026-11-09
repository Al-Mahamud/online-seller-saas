import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, Minus, Plus, X } from "lucide-react";
import { useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { can, useBusiness } from "@/api/business";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorText } from "@/api/query";
import { LedgerDialog } from "@/components/layout/LedgerDialog";
import { SearchBox } from "@/components/layout/SearchBox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { type ProductSummary, productPickerQuery, productQuery, type Variant } from "@/features/catalog/api";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import { cn } from "@/lib/utils";
import { DAMAGE_REASONS, type DamageReason, damagesChanged } from "./api";

const MAX_QUANTITY = 1_000_000;
/** The first few matches are enough to pick from; more typing narrows them. */
const SHOWN_MATCHES = 5;

interface Line {
  variantId: string;
  name: string;
  available: number;
  /** Per item, for managers and owners; null without a cost price. */
  cost: number | null;
  quantity: string;
}

type Check = { state: "ok"; quantity: number } | { state: "bad"; problem: "number" | "tooMany" };

function check(line: Line): Check {
  const quantity = parseCount(line.quantity);
  if (quantity === null || quantity < 1 || quantity > MAX_QUANTITY) {
    return { state: "bad", problem: "number" };
  }
  return quantity > line.available ? { state: "bad", problem: "tooMany" } : { state: "ok", quantity };
}

const nameOf = (product: { name: string }, variant: Variant) =>
  variant.label ? `${product.name}, ${variant.label}` : product.name;

/**
 * Damaged or lost goods, several products at once, with what happened: all of it comes off the stock
 * or none. Only what can be sold can be damaged here; what's held for orders belongs to them.
 */
export default function RecordDamageDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const queryClient = useQueryClient();
  const canSeeMoney = can(useBusiness(), "COSTS");
  const [search, setSearch] = useState("");
  const [openId, setOpenId] = useState<string>();
  const [lines, setLines] = useState<Line[]>([]);
  const [reason, setReason] = useState<DamageReason>();
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string>();
  const [tried, setTried] = useState(false);
  // Kept for retries of this one change, so a double tap or a bad connection never makes two.
  const idempotencyKey = useRef(newIdempotencyKey());
  const products = useInfiniteQuery({ ...productPickerQuery(search), enabled: search !== "" });
  const matches = (products.data?.pages.flatMap((page) => page.items) ?? []).slice(0, SHOWN_MATCHES);

  const checks = lines.map((line) => ({ line, result: check(line) }));
  const counted = checks.flatMap(({ line, result }) => (result.state === "ok" ? [{ line, ...result }] : []));
  const items = counted.reduce((sum, { quantity }) => sum + quantity, 0);
  const loss = counted.reduce((sum, { line, quantity }) => sum + (line.cost ?? 0) * quantity, 0);

  const record = useMutation({
    mutationFn: () =>
      call(
        api.POST("/api/v1/stock/adjustments", {
          body: {
            lines: counted.map(({ line, quantity }) => ({
              variantId: line.variantId,
              kind: "DAMAGE" as const,
              quantity,
            })),
            reason,
            note: note.trim() || undefined,
          },
          headers: { "Idempotency-Key": idempotencyKey.current },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      damagesChanged(queryClient);
      toast.success(t("damages.dialog.done"));
      onClose();
    },
    onError: (error) => {
      // The server answered: this attempt is finished, so a new one is a new change.
      if (error instanceof ApiError) {
        idempotencyKey.current = newIdempotencyKey();
        void queryClient.invalidateQueries({ queryKey: ["product"] });
      }
      setProblem(errorText(error));
    },
  });

  function add(product: { name: string }, variant: Variant) {
    setLines((current) =>
      current.some((line) => line.variantId === variant.id)
        ? current
        : [
            ...current,
            {
              variantId: variant.id,
              name: nameOf(product, variant),
              available: variant.available,
              cost: canSeeMoney ? (variant.costPrice ?? null) : null,
              quantity: "1",
            },
          ],
    );
    // "Add at least one product" is answered now.
    setProblem((current) => (current === t("damages.dialog.nothing") ? undefined : current));
  }

  function change(variantId: string, quantity: string) {
    setLines((current) => current.map((line) => (line.variantId === variantId ? { ...line, quantity } : line)));
  }

  function submit() {
    setTried(true);
    if (lines.length === 0) {
      setProblem(t("damages.dialog.nothing"));
      return;
    }
    if (checks.some(({ result }) => result.state === "bad")) {
      setProblem(undefined);
      return;
    }
    if (!reason) {
      setProblem(t("damages.dialog.pickReason"));
      return;
    }
    setProblem(undefined);
    record.mutate();
  }

  const figures = [
    { label: t("damages.dialog.figureItems"), value: t("damages.count", { count: format.number(items) }) },
  ];
  if (canSeeMoney) {
    figures.push({ label: t("damages.loss"), value: format.taka(loss) });
  }
  return (
    <LedgerDialog
      title={t("damages.record")}
      description={t("damages.dialog.description")}
      figures={figures}
      problem={problem}
      action={items > 0 ? t("damages.dialog.save", { count: format.number(items) }) : t("damages.dialog.saveEmpty")}
      pending={record.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <div className="flex flex-col gap-1.5">
        <p className="text-[15px] font-semibold">{t("damages.dialog.which")}</p>
        <SearchBox
          label={t("damages.dialog.which")}
          hint={t("damages.dialog.whichHint")}
          value={search}
          onChange={(value) => {
            setSearch(value);
            setOpenId(undefined);
          }}
        />
        {search !== "" && products.isSuccess && matches.length === 0 && (
          <p className="px-1 text-sm text-muted-foreground">{t("damages.dialog.noProduct")}</p>
        )}
        {matches.length > 0 && (
          <ul aria-label={t("damages.dialog.found")} className="rounded-[10px] border p-1">
            {matches.map((product) => (
              <Match
                key={product.id}
                product={product}
                open={openId === product.id}
                chosen={lines.map((line) => line.variantId)}
                onToggle={() => setOpenId(openId === product.id ? undefined : product.id)}
                onAdd={add}
              />
            ))}
          </ul>
        )}
      </div>

      {lines.length === 0 ? (
        <p className="rounded-xl border-[1.5px] border-dashed px-4 py-4 text-center text-[15px] text-muted-foreground">
          {t("damages.dialog.empty")}
        </p>
      ) : (
        <section aria-labelledby={`${id}-lines`}>
          <h3 id={`${id}-lines`} className="mb-1.5 text-[17px] font-bold">
            {t("damages.dialog.howMany")}
          </h3>
          <ul className="rounded-xl border">
            {checks.map(({ line, result }, index) => {
              const quantity = parseCount(line.quantity) ?? 0;
              const inputId = `${id}-${line.variantId}`;
              const problemId = `${inputId}-problem`;
              return (
                <li key={line.variantId} className={cn("flex flex-col gap-1.5 px-3 py-2.5", index > 0 && "border-t")}>
                  <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
                    <div className="min-w-0 flex-[1_1_180px]">
                      <Label htmlFor={inputId} className="block text-[15px] font-bold break-words">
                        {line.name}
                      </Label>
                      <p className="text-sm text-muted-foreground">
                        {t("damages.dialog.inStock", { count: format.number(line.available) })}
                      </p>
                    </div>
                    <div className="flex flex-none items-center gap-1">
                      <button
                        type="button"
                        aria-label={t("damages.dialog.less")}
                        className="flex size-11 cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] bg-card hover:bg-muted disabled:cursor-default disabled:opacity-50"
                        disabled={quantity <= 1}
                        onClick={() => change(line.variantId, String(Math.max(1, quantity - 1)))}
                      >
                        <Minus aria-hidden="true" className="size-4.5" />
                      </button>
                      <Input
                        id={inputId}
                        inputMode="numeric"
                        aria-label={t("damages.dialog.quantityOf", { name: line.name })}
                        aria-invalid={tried && result.state === "bad" ? true : undefined}
                        aria-describedby={result.state === "bad" ? problemId : undefined}
                        className="h-11 w-14 text-center text-[17px] font-bold"
                        value={line.quantity}
                        onChange={(event) => change(line.variantId, event.target.value)}
                      />
                      <button
                        type="button"
                        aria-label={t("damages.dialog.more")}
                        className="flex size-11 cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] bg-card hover:bg-muted"
                        onClick={() => change(line.variantId, String(quantity + 1))}
                      >
                        <Plus aria-hidden="true" className="size-4.5" />
                      </button>
                      <button
                        type="button"
                        aria-label={t("damages.dialog.remove", { name: line.name })}
                        className="flex size-11 cursor-pointer items-center justify-center rounded-[10px] text-muted-foreground hover:bg-muted"
                        onClick={() => setLines((current) => current.filter((other) => other !== line))}
                      >
                        <X aria-hidden="true" className="size-5" />
                      </button>
                    </div>
                  </div>
                  {result.state === "bad" ? (
                    <p
                      id={problemId}
                      className="rounded-lg bg-danger-soft px-2.5 py-1.75 text-sm font-semibold text-danger-ink"
                    >
                      {result.problem === "tooMany"
                        ? t("damages.dialog.tooMany", { count: format.number(line.available) })
                        : t("damages.dialog.badNumber")}
                    </p>
                  ) : (
                    <p className="flex justify-between gap-3 text-sm text-muted-foreground">
                      <span>
                        {t("damages.dialog.left", { count: format.number(line.available - result.quantity) })}
                      </span>
                      {canSeeMoney && line.cost !== null && (
                        <span>
                          {t("damages.dialog.lineLoss", { amount: format.taka(line.cost * result.quantity) })}
                        </span>
                      )}
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      <fieldset className="min-w-0">
        <legend className="mb-1.5 text-[15px] font-semibold">{t("damages.dialog.what")}</legend>
        <div className="flex flex-wrap gap-1.5">
          {DAMAGE_REASONS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={reason === option}
              className={cn(
                "min-h-11 cursor-pointer rounded-full border-[1.5px] px-3.5 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring",
                reason === option
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-input bg-card hover:bg-muted",
              )}
              onClick={() => {
                setReason(option);
                setProblem((current) => (current === t("damages.dialog.pickReason") ? undefined : current));
              }}
            >
              {t(`damages.reason.${option}`)}
            </button>
          ))}
        </div>
      </fieldset>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-note`} className="text-[15px] font-semibold">
          {t("damages.dialog.note")}
        </Label>
        <Textarea
          id={`${id}-note`}
          rows={2}
          maxLength={500}
          placeholder={t("damages.dialog.noteHint")}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </div>

      {canSeeMoney && <p className="text-sm text-muted-foreground">{t("damages.dialog.costNote")}</p>}
    </LedgerDialog>
  );
}

/** A product found by the search; opened, its variants that can be sold, each to add. */
function Match({
  product,
  open,
  chosen,
  onToggle,
  onAdd,
}: {
  product: ProductSummary;
  open: boolean;
  chosen: string[];
  onToggle: () => void;
  onAdd: (product: { name: string }, variant: Variant) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  // Read again on opening: the stock shown has to be what's there now.
  const detail = useQuery({ ...productQuery(product.id), enabled: open, staleTime: 0 });
  const variants = (detail.data?.variants ?? []).filter((variant) => variant.status === "ACTIVE");
  return (
    <li>
      <button
        type="button"
        aria-expanded={open}
        className="flex min-h-12 w-full cursor-pointer items-center justify-between gap-3 rounded-lg px-2.5 py-1.5 text-left hover:bg-muted"
        onClick={onToggle}
      >
        <span className="min-w-0">
          <span className="block text-[15px] font-semibold break-words">{product.name}</span>
          <span className="block text-sm text-muted-foreground">
            {t("damages.dialog.inStock", { count: format.number(product.available) })}
          </span>
        </span>
        <ChevronDown aria-hidden="true" className={cn("size-5 flex-none transition-transform", open && "rotate-180")} />
      </button>
      {open && detail.data && (
        <ul className="mb-1 ml-2.5 border-l-2 pl-2">
          {variants.map((variant) => {
            const added = chosen.includes(variant.id);
            return (
              <li key={variant.id}>
                <button
                  type="button"
                  disabled={added || variant.available < 1}
                  className="flex min-h-12 w-full cursor-pointer items-center justify-between gap-3 rounded-lg px-2.5 py-1.5 text-left hover:bg-muted disabled:cursor-default disabled:hover:bg-transparent"
                  onClick={() => onAdd(detail.data, variant)}
                >
                  <span className="min-w-0">
                    <span className="block text-[15px] break-words">{variant.label || product.name}</span>
                    <span className="block text-sm text-muted-foreground">
                      {t("damages.dialog.inStock", { count: format.number(variant.available) })}
                    </span>
                  </span>
                  <span
                    className={cn(
                      "flex-none text-[15px] font-bold",
                      added || variant.available < 1 ? "text-muted-foreground" : "text-primary",
                    )}
                  >
                    {added ? t("damages.dialog.added") : t("damages.dialog.add")}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </li>
  );
}
