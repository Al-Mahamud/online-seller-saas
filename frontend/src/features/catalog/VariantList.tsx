import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Pagination } from "@/components/layout/Pagination";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { takaInput } from "@/lib/money";
import { type Paging, pagedQuery } from "@/lib/paging";
import { cn } from "@/lib/utils";
import { type Product, productChanged, type Variant } from "./api";
import { optionalCount, optionalTaka, taka, variantRow } from "./schemas";

/** Variants made from a row have only these; older ones may have other options, which can't be renamed here. */
const NAME_OPTION = "Variant";
const SIZE_OPTION = "Size";
const canRename = (variant: Variant) =>
  Object.keys(variant.attributes).every((option) => option === NAME_OPTION || option === SIZE_OPTION);
const MAX_VARIANTS = 100;

/** Wide enough for the table beside the sidebar; narrower, each variant is a card. */
const TABLE_COLUMNS = {
  manager:
    "min-[1120px]:grid-cols-[52px_minmax(170px,1.6fr)_minmax(70px,0.7fr)_minmax(70px,0.7fr)_minmax(44px,0.4fr)_minmax(44px,0.4fr)_minmax(70px,0.6fr)_176px]",
  staff:
    "min-[1120px]:grid-cols-[52px_minmax(170px,1.6fr)_minmax(70px,0.7fr)_minmax(44px,0.4fr)_minmax(44px,0.4fr)_minmax(70px,0.6fr)_96px]",
};

/**
 * The product's variants: a row for each, with its stock and price. Managers open one to edit it.
 * Staff get no cost from the server, so there is nothing to hide here.
 */
export function VariantList({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<{ variant: Variant; view: "edit" | "history" }>();
  const [adding, setAdding] = useState(false);
  // Reads the variant from the latest answer, so an open dialog follows its changes.
  const current = open && product.variants.find((variant) => variant.id === open.variant.id);
  const canAdd = canEdit && product.variants.length < MAX_VARIANTS;
  const addButton = (
    <button
      type="button"
      onClick={() => setAdding(true)}
      className="flex min-h-11 w-full cursor-pointer items-center justify-center gap-1.5 rounded-[10px] border-[1.5px] border-dashed border-primary bg-card px-3.5 text-[15px] font-semibold text-primary hover:bg-accent min-[1120px]:w-auto"
    >
      <Plus aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
      {t("products.variantRows.addShort")}
    </button>
  );
  const columns = canEdit ? TABLE_COLUMNS.manager : TABLE_COLUMNS.staff;
  return (
    <section aria-labelledby="variants" className="overflow-hidden rounded-[14px] border bg-card">
      <div className="flex items-center justify-between gap-5 border-b px-4 py-3 min-[1120px]:px-5 min-[1120px]:py-3.5">
        <h2 id="variants" className="min-h-11 content-center text-[19px] font-bold">
          {t("products.variants")}
        </h2>
        {canAdd && <div className="max-[1119px]:hidden">{addButton}</div>}
      </div>
      <div
        aria-hidden="true"
        className={cn(
          "hidden items-end gap-2.5 border-b py-2 pr-5 text-[13px] font-semibold text-muted-foreground min-[1120px]:grid",
          columns,
        )}
      >
        <span />
        <span className="pl-2">{t("products.detail.nameAndSku")}</span>
        <span className="text-right">{t("products.detail.sellColumn")}</span>
        {canEdit && <span className="text-right">{t("products.detail.costColumn")}</span>}
        <span className="text-right">{t("products.detail.onHandColumn")}</span>
        <span className="text-right">{t("products.detail.reservedColumn")}</span>
        <span className="text-right">{t("products.detail.available")}</span>
        <span />
      </div>
      <div className="relative">
        <div
          aria-hidden="true"
          className="absolute inset-y-0 left-[51px] hidden w-0.5 bg-marigold min-[1120px]:block"
        />
        <ul className="flex flex-col">
          {product.variants.map((variant, index) => (
            <VariantRow
              key={variant.id}
              variant={variant}
              number={index + 1}
              canEdit={canEdit}
              columns={columns}
              onEdit={() => setOpen({ variant, view: "edit" })}
              onHistory={() => setOpen({ variant, view: "history" })}
            />
          ))}
        </ul>
      </div>
      {canAdd && <div className="px-4 pt-1 pb-4 min-[1120px]:hidden">{addButton}</div>}
      {adding && <AddVariantDialog product={product} onClose={() => setAdding(false)} />}
      {open?.view === "edit" && current && (
        <VariantDialog product={product} variant={current} onClose={() => setOpen(undefined)} />
      )}
      {open?.view === "history" && current && <HistoryDialog variant={current} onClose={() => setOpen(undefined)} />}
    </section>
  );
}

function Count({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <p className="min-[1120px]:text-right">
      <span className="block text-[13px] text-muted-foreground min-[1120px]:sr-only">{label}</span>
      <span
        className={cn("block text-[17px] font-semibold min-[1120px]:text-base min-[1120px]:font-normal", className)}
      >
        {value}
      </span>
    </p>
  );
}

function VariantRow({
  variant,
  number,
  canEdit,
  columns,
  onEdit,
  onHistory,
}: {
  variant: Variant;
  number: number;
  canEdit: boolean;
  columns: string;
  onEdit: () => void;
  onHistory: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const name = variant.label || variant.sku;
  const archived = variant.status === "ARCHIVED";
  return (
    <li
      className={cn(
        "grid grid-cols-6 gap-x-2.5 gap-y-2 border-b px-4 py-3 last:border-b-0 min-[1120px]:min-h-16 min-[1120px]:items-center min-[1120px]:border-b-0 min-[1120px]:py-1.5 min-[1120px]:pr-5 min-[1120px]:pl-0",
        columns,
        archived && "bg-muted/60",
      )}
    >
      <span
        aria-hidden="true"
        className="hidden text-center text-[15px] font-bold text-warning-foreground min-[1120px]:block"
      >
        {format.number(number)}
      </span>
      <div className="col-span-4 row-span-2 min-w-0 min-[1120px]:col-span-1 min-[1120px]:row-span-1 min-[1120px]:pl-2">
        <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          <span className="text-[17px] font-bold min-[1120px]:text-base min-[1120px]:font-semibold">{name}</span>
          {variant.lowStock && (
            <span className="rounded-full bg-warning px-2 text-[13px] font-semibold text-warning-foreground">
              {t("products.lowStock")}
            </span>
          )}
          {archived && (
            <span className="rounded-full bg-background px-2 text-[13px] font-semibold text-[#2B342F]">
              {t("products.status.ARCHIVED")}
            </span>
          )}
        </p>
        <p className="text-[13px] break-all text-muted-foreground">{variant.sku}</p>
      </div>
      <p className="col-span-2 text-right text-[17px] font-bold min-[1120px]:col-span-1 min-[1120px]:text-base">
        <span className="sr-only">{t("products.detail.sellColumn")} </span>
        {format.taka(variant.price)}
      </p>
      {canEdit && (
        <p className="col-span-2 text-right text-[13px] text-muted-foreground min-[1120px]:col-span-1 min-[1120px]:text-base">
          <span className="min-[1120px]:sr-only">{t("products.detail.costColumn")} </span>
          {variant.costPrice === undefined ? "–" : format.taka(variant.costPrice)}
        </p>
      )}
      {/* biome-ignore lint/a11y/useSemanticElements: a fieldset can't dissolve into the table's columns. */}
      <div
        role="group"
        aria-label={t("products.stockLine", {
          onHand: format.number(variant.onHand),
          reserved: format.number(variant.reserved),
          available: format.number(variant.available),
        })}
        className="col-span-6 grid grid-cols-3 gap-2 rounded-[10px] bg-background px-2.5 py-2 min-[1120px]:contents"
      >
        <Count label={t("products.detail.onHandColumn")} value={format.number(variant.onHand)} />
        <Count label={t("products.detail.reservedColumn")} value={format.number(variant.reserved)} />
        <Count
          label={t("products.detail.available")}
          value={format.number(variant.available)}
          className={cn(
            "font-bold min-[1120px]:font-bold",
            variant.lowStock ? "text-warning-foreground" : "text-primary",
          )}
        />
      </div>
      <div className="col-span-6 grid auto-cols-fr grid-flow-col gap-2 min-[1120px]:col-span-1 min-[1120px]:flex min-[1120px]:justify-end min-[1120px]:gap-1.5">
        <Button
          variant="outline"
          className="h-11 rounded-[10px] border-[1.5px] px-3 font-semibold"
          aria-label={`${t("stock.history")}: ${name}`}
          onClick={onHistory}
        >
          {t("stock.history")}
        </Button>
        {canEdit && (
          <Button
            variant="outline"
            className="h-11 rounded-[10px] border-[1.5px] px-3 font-semibold"
            aria-label={`${t("products.edit")}: ${name}`}
            onClick={onEdit}
          >
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
  const [paging, setPaging] = useState<Paging>({});
  const top = useRef<HTMLDivElement>(null);
  const history = useQuery(
    pagedQuery(["movements", variant.id, paging], () =>
      call(api.GET("/api/v1/variants/{id}/movements", { params: { path: { id: variant.id }, query: paging } })),
    ),
  );
  const movements = history.data?.items ?? [];
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader ref={top}>
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
        <Pagination result={history.data} onChange={setPaging} scrollTo={top} />
      </DialogContent>
    </Dialog>
  );
}
