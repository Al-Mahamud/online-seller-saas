import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { categoriesQuery, flatCategories, type Product, productChanged, productQuery, type Variant } from "./api";
import { BackToProducts } from "./LowStockPage";
import { ProductImages } from "./ProductImages";
import { StockDialog } from "./StockDialog";
import { TagPicker } from "./TagPicker";
import { VariantList } from "./VariantList";

const route = getRouteApi("/_app/products/$productId");

/** One product: everyone sees it and changes its stock; managers and owners also edit it. */
export function ProductPage() {
  const { t } = useTranslation();
  const { productId } = route.useParams();
  const business = useBusiness();
  const { data: product } = useQuery(productQuery(productId));
  const [changingStock, setChangingStock] = useState(false);
  if (!product) {
    return <BackToProducts />;
  }
  const manager = hasRole(business, "MANAGER");
  const changeStock = (
    <Button
      size="lg"
      className="h-13 w-full rounded-[10px] text-lg font-semibold md:h-11 md:w-auto md:text-base"
      onClick={() => setChangingStock(true)}
    >
      {t("stock.change")}
    </Button>
  );
  return (
    <div className="flex w-full flex-col gap-4.5">
      {/* On phones the title and the totals share one dark header; from 768 px each stands alone. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-3 max-md:bg-ledger max-md:pt-1.5 max-md:pr-4.5 max-md:pb-9 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex flex-wrap items-end justify-between gap-x-5 gap-y-3">
          <div className="min-w-0">
            <div className="max-md:[&_a]:text-marigold-text">
              <BackToProducts />
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <h1 className="text-[28px] leading-tight font-bold break-words md:text-[32px]">{product.name}</h1>
              <StatusBadge status={product.status} />
            </div>
          </div>
          <div className="flex flex-wrap gap-2.5 max-md:hidden">
            {manager && <ArchiveButton product={product} />}
            {changeStock}
          </div>
        </div>
        <Totals product={product} />
      </div>

      <div className="flex flex-col gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-8 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-4">
        <div className="md:hidden">{changeStock}</div>
        <div className="flex flex-wrap items-stretch gap-4.5">
          <ProductImages product={product} canEdit={manager} />
          <Details product={product} canEdit={manager} />
        </div>
        <VariantList product={product} canEdit={manager} />
        {manager && (
          <div className="flex flex-col md:hidden">
            <ArchiveButton product={product} />
          </div>
        )}
      </div>
      {changingStock && <StockDialog product={product} onClose={() => setChangingStock(false)} />}
    </div>
  );
}

function Figure({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div className="border-ledger-line max-md:border-t max-md:pt-1.5">
      <p className="text-sm font-semibold text-ledger-text">{label}</p>
      <p className={cn("text-[28px] leading-tight font-bold md:text-[34px]", className)}>{value}</p>
    </div>
  );
}

/** What the variants on sale add up to. */
function Totals({ product }: { product: Product }) {
  const { t } = useTranslation();
  const format = useFormat();
  const active = product.variants.filter((variant) => variant.status === "ACTIVE");
  const add = (read: (variant: Variant) => number) => active.reduce((sum, variant) => sum + read(variant), 0);
  const prices = active.map((variant) => variant.price);
  const lowest = Math.min(...prices);
  const highest = Math.max(...prices);
  const priceRange =
    prices.length === 0
      ? "–"
      : lowest === highest
        ? format.taka(lowest)
        : `${format.taka(lowest)}–${format.taka(highest).replace("৳", "")}`;
  return (
    <section
      aria-label={t("products.detail.summary")}
      className="grid grid-cols-2 gap-x-3.5 gap-y-2.5 md:relative md:grid-cols-[repeat(auto-fit,minmax(150px,1fr))] md:gap-x-6 md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-4.5 md:pr-7 md:pl-15 md:text-white"
    >
      <div aria-hidden="true" className="absolute inset-y-0 left-8 hidden w-0.5 bg-marigold md:block" />
      <Figure label={t("products.detail.onHand")} value={format.number(add((variant) => variant.onHand))} />
      <Figure label={t("products.detail.reserved")} value={format.number(add((variant) => variant.reserved))} />
      <Figure
        label={t("products.detail.available")}
        value={format.number(add((variant) => variant.available))}
        className="text-marigold-text"
      />
      <Figure
        label={t("products.detail.priceRange")}
        value={priceRange}
        className="text-[19px] leading-[1.85] md:text-[26px] md:leading-[1.6]"
      />
    </section>
  );
}

export function StatusBadge({ status }: { status: Variant["status"] }) {
  const { t } = useTranslation();
  return status === "ACTIVE" ? (
    <span className="rounded-full bg-accent px-2.5 text-[13px] font-semibold text-accent-foreground">
      {t("products.status.ACTIVE")}
    </span>
  ) : (
    <span className="rounded-full bg-warning px-2.5 text-[13px] font-semibold text-warning-foreground">
      {t("products.status.ARCHIVED")}
    </span>
  );
}

function ArchiveButton({ product }: { product: Product }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const params = { params: { path: { id: product.id } } };
  const change = useMutation({
    mutationFn: () =>
      call(
        product.status === "ACTIVE"
          ? api.POST("/api/v1/products/{id}/archive", params)
          : api.POST("/api/v1/products/{id}/restore", params),
      ),
    onSuccess: (updated) => productChanged(queryClient, updated),
  });
  return (
    <Button
      variant="outline"
      className="h-12 rounded-[10px] border-[1.5px] text-base font-semibold md:h-11"
      disabled={change.isPending}
      onClick={() => change.mutate()}
    >
      {product.status === "ACTIVE" ? t("products.archive") : t("products.restore")}
    </Button>
  );
}

/** To read; changing them takes a deliberate step, so nothing changes by accident. */
function Details({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const { t } = useTranslation();
  const { data: categories } = useQuery(categoriesQuery);
  const [editing, setEditing] = useState(false);
  const category = flatCategories(categories ?? []).find((option) => option.id === product.categoryId);
  return (
    <section
      aria-labelledby="details"
      className="flex min-w-0 flex-[7_1_420px] flex-col gap-3.5 rounded-[14px] border bg-card p-4 md:px-5 md:py-4.5"
    >
      <div className="flex min-h-11 items-center justify-between gap-3">
        <h2 id="details" className="text-[19px] font-bold">
          {t("products.details")}
        </h2>
        {canEdit && (
          <Button
            variant="outline"
            className="h-11 rounded-[10px] border-[1.5px] border-primary px-3.5 font-semibold text-primary hover:text-primary"
            onClick={() => setEditing(true)}
          >
            {t("products.detail.editDetails")}
          </Button>
        )}
      </div>
      <div>
        <p className="text-sm text-muted-foreground">{t("products.detail.description")}</p>
        <p
          className={cn("mt-0.5 leading-relaxed whitespace-pre-wrap", !product.description && "text-muted-foreground")}
        >
          {product.description || t("products.detail.noDescription")}
        </p>
      </div>
      <div className="grid gap-x-6 gap-y-3.5 border-t pt-3 sm:grid-cols-2">
        <div>
          <p className="text-sm text-muted-foreground">{t("products.category")}</p>
          <p className="mt-0.5 font-semibold">{category?.name ?? t("products.noCategory")}</p>
        </div>
        <div>
          <p className="text-sm text-muted-foreground">{t("products.tags")}</p>
          {product.tags.length === 0 ? (
            <p className="mt-0.5 text-muted-foreground">{t("products.detail.noTags")}</p>
          ) : (
            <div className="mt-1 flex flex-wrap gap-1.5">
              {product.tags.map((tag) => (
                <span key={tag.id} className="rounded-full bg-muted px-2.5 text-sm">
                  {tag.name}
                </span>
              ))}
            </div>
          )}
        </div>
      </div>
      {editing && <DetailsDialog product={product} onClose={() => setEditing(false)} />}
    </section>
  );
}

const detailsSchema = z.object({
  name: z.string().check(z.trim(), z.minLength(1, "field.required"), z.maxLength(200, "field.nameLong")),
  description: z.string().check(z.trim(), z.maxLength(5000, "field.footerLong")),
  categoryId: z.string(),
});

function DetailsDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: categories } = useQuery(categoriesQuery);
  const [tagIds, setTagIds] = useState(product.tags.map((tag) => tag.id));
  const form = useForm({
    resolver: zodResolver(detailsSchema),
    values: {
      name: product.name,
      description: product.description ?? "",
      // Waits for the list: a select can't show a choice it doesn't have yet.
      categoryId: categories ? (product.categoryId ?? "") : "",
    },
  });
  const save = useMutation({
    mutationFn: (values: z.output<typeof detailsSchema>) =>
      call(
        api.PATCH("/api/v1/products/{id}", {
          params: { path: { id: product.id } },
          body: {
            name: values.name,
            description: values.description,
            categoryId: values.categoryId || undefined,
            clearCategory: !values.categoryId,
            tagIds,
            version: product.version,
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      productChanged(queryClient, updated);
      toast.success(t("account.saved"));
      onClose();
    },
    onError: (error) => {
      showServerError(form, error);
      // Someone else changed it: the form takes the latest, which the message explains.
      void queryClient.invalidateQueries(productQuery(product.id));
    },
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{t("products.detail.editDetails")}</DialogTitle>
        </DialogHeader>
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
          <FormError form={form} />
          <TextField form={form} name="name" label={t("products.name")} />
          <TextAreaField form={form} name="description" label={t("products.description")} />
          <SelectField form={form} name="categoryId" label={t("products.category")}>
            <option value="">{t("products.noCategory")}</option>
            {flatCategories(categories ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </SelectField>
          <TagPicker selected={tagIds} onChange={setTagIds} />
          <div className="flex flex-col-reverse gap-2.5 sm:flex-row sm:justify-end">
            <Button type="button" variant="outline" onClick={onClose}>
              {t("products.variantRows.cancel")}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {t("account.save")}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
