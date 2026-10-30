import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorText } from "@/api/query";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import type { Product } from "./api";

type Kind = "RECEIVE" | "DAMAGE" | "COUNT";
const KINDS: Kind[] = ["RECEIVE", "DAMAGE", "COUNT"];
const MAX_QUANTITY = 1_000_000;

/**
 * A delivery, damage or a shelf count, for any of the product's variants at once: all of it happens
 * or none. Empty boxes are left alone.
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
      }
      setProblem(errorText(error));
    },
  });

  function submit() {
    const lines = [];
    for (const variant of variants) {
      const text = quantities[variant.id]?.trim();
      if (text) {
        const quantity = parseCount(text);
        if (quantity === null || quantity > MAX_QUANTITY || (kind !== "COUNT" && quantity === 0)) {
          setProblem(t("stock.badQuantity", { name: variant.label || variant.sku }));
          return;
        }
        lines.push({ variantId: variant.id, kind, quantity });
      }
    }
    if (lines.length === 0) {
      setProblem(t("stock.nothing"));
      return;
    }
    setProblem(undefined);
    adjust.mutate(lines);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("stock.change")}</DialogTitle>
        </DialogHeader>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          {problem && (
            <Alert variant="destructive">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
          <fieldset className="flex flex-col gap-1">
            <legend className="mb-1 text-[15px] font-semibold">{t("stock.what")}</legend>
            {KINDS.map((option) => (
              <Label key={option} className="min-h-11 text-base font-normal">
                <input
                  type="radio"
                  name={`${id}-kind`}
                  className="size-5 accent-primary"
                  checked={kind === option}
                  onChange={() => setKind(option)}
                />
                {t(`stock.kind.${option}`)}
              </Label>
            ))}
          </fieldset>
          <p className="text-sm text-muted-foreground">{t(`stock.hint.${kind}`)}</p>
          {variants.map((variant) => (
            <div key={variant.id} className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-${variant.id}`} className="text-[15px] leading-normal font-semibold">
                {variant.label || product.name}
              </Label>
              <Input
                id={`${id}-${variant.id}`}
                inputMode="numeric"
                aria-describedby={`${id}-${variant.id}-now`}
                value={quantities[variant.id] ?? ""}
                onChange={(event) => setQuantities({ ...quantities, [variant.id]: event.target.value })}
              />
              <p id={`${id}-${variant.id}-now`} className="text-sm text-muted-foreground">
                {t("stock.now", { count: format.number(variant.onHand) })}
              </p>
            </div>
          ))}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${id}-note`} className="text-[15px] leading-normal font-semibold">
              {t("stock.note")}
            </Label>
            <Input
              id={`${id}-note`}
              value={note}
              maxLength={500}
              placeholder={t("stock.noteHint")}
              onChange={(event) => setNote(event.target.value)}
            />
          </div>
          <Button type="submit" disabled={adjust.isPending}>
            {t("stock.save")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
