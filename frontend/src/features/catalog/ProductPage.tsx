import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
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
  return (
    <div className="flex max-w-4xl flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <BackToProducts />
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight break-words md:text-3xl">{product.name}</h1>
            <StatusBadge status={product.status} />
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => setChangingStock(true)}>{t("stock.change")}</Button>
          {manager && <ArchiveButton product={product} />}
        </div>
      </div>
      {manager ? <DetailsSection product={product} /> : <ReadOnlyDetails product={product} />}
      <ProductImages product={product} canEdit={manager} />
      <VariantList product={product} canEdit={manager} />
      {changingStock && <StockDialog product={product} onClose={() => setChangingStock(false)} />}
    </div>
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
    <Button variant="outline" disabled={change.isPending} onClick={() => change.mutate()}>
      {product.status === "ACTIVE" ? t("products.archive") : t("products.restore")}
    </Button>
  );
}

function ReadOnlyDetails({ product }: { product: Product }) {
  const { t } = useTranslation();
  if (!product.description && product.tags.length === 0) {
    return null;
  }
  return (
    <Section title={t("products.details")}>
      {product.description && <p className="whitespace-pre-wrap">{product.description}</p>}
      {product.tags.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {product.tags.map((tag) => (
            <span key={tag.id} className="rounded-full bg-muted px-2.5 text-sm">
              {tag.name}
            </span>
          ))}
        </div>
      )}
    </Section>
  );
}

const detailsSchema = z.object({
  name: z.string().check(z.trim(), z.minLength(1, "field.required"), z.maxLength(200, "field.nameLong")),
  description: z.string().check(z.trim(), z.maxLength(5000, "field.footerLong")),
  categoryId: z.string(),
});

function DetailsSection({ product }: { product: Product }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: categories } = useQuery(categoriesQuery);
  const [tagIds, setTagIds] = useState(product.tags.map((tag) => tag.id));
  useEffect(() => setTagIds(product.tags.map((tag) => tag.id)), [product.tags]);
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
    },
    onError: (error) => {
      showServerError(form, error);
      // Someone else changed it: the form takes the latest, which the message explains.
      void queryClient.invalidateQueries(productQuery(product.id));
    },
  });
  return (
    <Section title={t("products.details")}>
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
        <Button type="submit" className="self-start" disabled={save.isPending}>
          {t("account.save")}
        </Button>
      </form>
    </Section>
  );
}
