import { zodResolver } from "@hookform/resolvers/zod";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { takaInput } from "@/lib/money";
import { type Product, productChanged, type Variant } from "./api";
import { optionalCount, optionalTaka, taka, variantRow } from "./schemas";

/** Variants made from a row have only these; older ones may have other options, which can't be renamed here. */
const NAME_OPTION = "Variant";
const SIZE_OPTION = "Size";
const canRename = (variant: Variant) =>
  Object.keys(variant.attributes).every((option) => option === NAME_OPTION || option === SIZE_OPTION);
const MAX_VARIANTS = 100;

/**
 * The product's variants: a row for each, with its stock and price. Managers open one to edit it.
 * Staff get no cost or margin from the server, so there is nothing to hide here.
 */
export function VariantList({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<{ variant: Variant; view: "edit" | "history" }>();
  const [adding, setAdding] = useState(false);
  // Reads the variant from the latest answer, so an open dialog follows its changes.
  const current = open && product.variants.find((variant) => variant.id === open.variant.id);
  return (
    <Section title={t("products.variants")}>
      <ul className="flex flex-col">
        {product.variants.map((variant) => (
          <VariantRow
            key={variant.id}
            variant={variant}
            canEdit={canEdit}
            onEdit={() => setOpen({ variant, view: "edit" })}
            onHistory={() => setOpen({ variant, view: "history" })}
          />
        ))}
      </ul>
      {canEdit && product.variants.length < MAX_VARIANTS && (
        <Button variant="outline" className="self-start" onClick={() => setAdding(true)}>
          <Plus aria-hidden="true" />
          {t("products.variantRows.addShort")}
        </Button>
      )}
      {adding && <AddVariantDialog product={product} onClose={() => setAdding(false)} />}
      {open?.view === "edit" && current && (
        <VariantDialog product={product} variant={current} onClose={() => setOpen(undefined)} />
      )}
      {open?.view === "history" && current && <HistoryDialog variant={current} onClose={() => setOpen(undefined)} />}
    </Section>
  );
}

function VariantRow({
  variant,
  canEdit,
  onEdit,
  onHistory,
}: {
  variant: Variant;
  canEdit: boolean;
  onEdit: () => void;
  onHistory: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const name = variant.label || variant.sku;
  return (
    <li className="flex flex-col gap-2 border-t py-3 first:border-t-0 first:pt-0 md:flex-row md:items-center md:justify-between">
      <div className="min-w-0">
        <p className="font-semibold">
          {name}
          {variant.status === "ARCHIVED" && (
            <span className="ml-2 text-[13px] font-semibold text-warning-foreground">
              {t("products.status.ARCHIVED")}
            </span>
          )}
        </p>
        <p className="text-sm break-all text-muted-foreground">{variant.sku}</p>
        <p className="text-sm">
          <span className="font-bold">{format.taka(variant.price)}</span>
          {variant.costPrice !== undefined && (
            <span className="text-muted-foreground">
              {" "}
              · {t("products.costShort", { amount: format.taka(variant.costPrice) })}
            </span>
          )}
          {variant.margin !== undefined && (
            <span className="font-semibold text-primary">
              {" "}
              · {t("products.marginShort", { amount: format.taka(variant.margin) })}
            </span>
          )}
        </p>
        <p className="text-sm text-muted-foreground">
          {t("products.stockLine", {
            onHand: format.number(variant.onHand),
            reserved: format.number(variant.reserved),
            available: format.number(variant.available),
          })}
        </p>
      </div>
      <div className="flex flex-none flex-wrap gap-2">
        <Button variant="outline" aria-label={`${t("stock.history")}: ${name}`} onClick={onHistory}>
          {t("stock.history")}
        </Button>
        {canEdit && (
          <Button variant="outline" aria-label={`${t("products.edit")}: ${name}`} onClick={onEdit}>
            {t("products.edit")}
          </Button>
        )}
      </div>
    </li>
  );
}

function AddVariantDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm({
    resolver: zodResolver(variantRow),
    defaultValues: { name: "", size: "", quantity: "", costPrice: "", price: "" },
  });
  const add = useMutation({
    mutationFn: (values: z.output<typeof variantRow>) =>
      call(
        api.POST("/api/v1/products/{id}/variants", {
          params: { path: { id: product.id } },
          body: { variant: values, version: product.version },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      productChanged(queryClient, updated);
      toast.success(t("account.saved"));
      onClose();
    },
    onError: (error) =>
      showServerError(form, error, { codes: { "variant.duplicate": "name" }, fields: { variants: "name" } }),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("products.variantRows.newTitle")}</DialogTitle>
          <DialogDescription>{product.name}</DialogDescription>
        </DialogHeader>
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => add.mutate(values))}>
          <FormError form={form} />
          <TextField
            form={form}
            name="name"
            label={t("products.variantRows.name")}
            hint={t("products.variantRows.newHint")}
            placeholder={t("products.variantRows.nameHint")}
            maxLength={30}
          />
          <TextField
            form={form}
            name="size"
            label={t("products.variantRows.size")}
            placeholder={t("products.variantRows.sizeHint")}
            maxLength={30}
          />
          <TextField form={form} name="quantity" label={t("products.variantRows.quantity")} inputMode="numeric" />
          <TextField
            form={form}
            name="costPrice"
            label={t("products.variantRows.cost")}
            hint={t("products.costHint")}
            inputMode="decimal"
          />
          <TextField form={form} name="price" label={t("products.variantRows.price")} inputMode="decimal" />
          <Button type="submit" disabled={add.isPending}>
            {t("products.variantRows.addShort")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

const variantSchema = z.object({
  name: variantRow.shape.name,
  size: variantRow.shape.size,
  price: taka,
  costPrice: optionalTaka,
  sku: z.string().check(z.trim(), z.maxLength(64, "field.skuLong")),
  lowStockThreshold: optionalCount(100_000),
});

function VariantDialog({ product, variant, onClose }: { product: Product; variant: Variant; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const renamable = canRename(variant);
  const form = useForm({
    resolver: zodResolver(variantSchema),
    defaultValues: {
      name: variant.attributes[NAME_OPTION] ?? "",
      size: variant.attributes[SIZE_OPTION] ?? "",
      price: takaInput(variant.price),
      costPrice: variant.costPrice === undefined ? "" : takaInput(variant.costPrice),
      sku: variant.sku,
      lowStockThreshold: variant.lowStockThreshold === null ? "" : String(variant.lowStockThreshold),
    },
  });
  // The product's own version changes too, so its page reads again.
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["product", product.id] });
    void queryClient.invalidateQueries({ queryKey: ["products"] });
    void queryClient.invalidateQueries({ queryKey: ["low-stock"] });
  };
  const save = useMutation({
    mutationFn: (values: z.output<typeof variantSchema>) =>
      call(
        api.PATCH("/api/v1/variants/{id}", {
          params: { path: { id: variant.id } },
          body: {
            ...(renamable ? { name: values.name, size: values.size } : {}),
            price: values.price,
            // An empty SKU makes the server generate one.
            sku: values.sku,
            costPrice: values.costPrice,
            clearCostPrice: values.costPrice === undefined,
            lowStockThreshold: values.lowStockThreshold,
            useDefaultThreshold: values.lowStockThreshold === undefined,
            version: variant.version,
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      refresh();
      toast.success(t("account.saved"));
      onClose();
    },
    onError: (error) => {
      showServerError(form, error, {
        codes: { "product.sku_taken": "sku", "variant.duplicate": "name" },
        fields: { variants: "name" },
      });
      refresh();
    },
  });
  const params = { params: { path: { id: variant.id } } };
  const changeStatus = useMutation({
    mutationFn: () =>
      call(
        variant.status === "ACTIVE"
          ? api.POST("/api/v1/variants/{id}/archive", params)
          : api.POST("/api/v1/variants/{id}/restore", params),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      refresh();
      onClose();
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{variant.label || product.name}</DialogTitle>
          <DialogDescription>{product.name}</DialogDescription>
        </DialogHeader>
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
          <FormError form={form} />
          {renamable && (
            <>
              <TextField form={form} name="name" label={t("products.variantRows.name")} maxLength={30} />
              <TextField form={form} name="size" label={t("products.variantRows.size")} maxLength={30} />
            </>
          )}
          <TextField form={form} name="price" label={t("products.price")} inputMode="decimal" />
          <TextField
            form={form}
            name="costPrice"
            label={t("products.cost")}
            hint={t("products.costHint")}
            inputMode="decimal"
          />
          <TextField form={form} name="sku" label={t("products.sku")} hint={t("products.skuHint")} maxLength={64} />
          <TextField
            form={form}
            name="lowStockThreshold"
            label={t("products.threshold")}
            hint={t("products.thresholdHint")}
            inputMode="numeric"
          />
          <Button type="submit" disabled={save.isPending}>
            {t("account.save")}
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={changeStatus.isPending}
            onClick={() => changeStatus.mutate()}
          >
            {variant.status === "ACTIVE" ? t("products.archiveVariant") : t("products.restoreVariant")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function HistoryDialog({ variant, onClose }: { variant: Variant; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const history = useInfiniteQuery({
    queryKey: ["movements", variant.id],
    queryFn: ({ pageParam }) =>
      call(
        api.GET("/api/v1/variants/{id}/movements", {
          params: { path: { id: variant.id }, query: { cursor: pageParam } },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const movements = history.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("stock.history")}</DialogTitle>
          <DialogDescription>{variant.label || variant.sku}</DialogDescription>
        </DialogHeader>
        {history.isSuccess && movements.length === 0 && <p className="text-muted-foreground">{t("stock.noHistory")}</p>}
        <ul className="flex flex-col">
          {movements.map((movement) => (
            <li key={movement.id} className="border-t py-2 first:border-t-0">
              <p className="flex justify-between gap-2 font-medium">
                <span>{t(`stock.movement.${movement.type}`)}</span>
                {movement.onHandChange !== 0 && (
                  <span dir="ltr">
                    {movement.onHandChange > 0 ? "+" : "−"}
                    {format.number(Math.abs(movement.onHandChange))}
                  </span>
                )}
              </p>
              {movement.note && <p className="text-sm">{movement.note}</p>}
              <p className="text-sm text-muted-foreground">
                {t("stock.after", { count: format.number(movement.onHandAfter) })} · {format.dateTime(movement.at)}
                {movement.by && ` · ${movement.by.name}`}
              </p>
            </li>
          ))}
        </ul>
        {history.hasNextPage && (
          <Button variant="outline" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
            {t("audit.more")}
          </Button>
        )}
      </DialogContent>
    </Dialog>
  );
}
