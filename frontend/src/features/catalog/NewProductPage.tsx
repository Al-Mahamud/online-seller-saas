import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { api, call } from "@/api/client";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { categoriesQuery, flatCategories, productChanged } from "./api";
import { BackToProducts } from "./LowStockPage";
import { type OptionDraft, OptionsEditor, optionsProblem } from "./OptionsEditor";
import { optionalCount, optionalTaka, taka } from "./schemas";
import { TagPicker } from "./TagPicker";

const schema = z.object({
  name: z.string().trim().min(1, "field.required").max(200, "field.nameLong"),
  description: z.string().trim().max(5000, "field.footerLong"),
  categoryId: z.string(),
  price: taka,
  costPrice: optionalTaka,
  sku: z.string().trim().max(64),
  openingStock: optionalCount(1_000_000),
});

export function NewProductPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: categories } = useQuery(categoriesQuery);
  const [options, setOptions] = useState<OptionDraft[]>([]);
  const [tagIds, setTagIds] = useState<string[]>([]);
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: { name: "", description: "", categoryId: "", price: "", costPrice: "", sku: "", openingStock: "" },
  });
  const create = useMutation({
    mutationFn: (values: z.output<typeof schema>) =>
      call(
        api.POST("/api/v1/products", {
          body: {
            ...values,
            description: values.description || undefined,
            categoryId: values.categoryId || undefined,
            sku: values.sku || undefined,
            tagIds,
            options: options.map((option) => ({ name: option.name.trim(), values: option.values })),
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: async (product) => {
      productChanged(queryClient, product);
      await navigate({ to: "/products/$productId", params: { productId: product.id }, replace: true });
    },
    onError: (error) => showServerError(form, error, { codes: { "product.sku_taken": "sku" } }),
  });

  return (
    <form
      noValidate
      className="flex max-w-2xl flex-col gap-5"
      onSubmit={form.handleSubmit((values) => {
        const problem = optionsProblem(options);
        if (problem) {
          form.setError("root", { type: "local", message: problem });
        } else {
          create.mutate(values);
        }
      })}
    >
      <div>
        <BackToProducts />
        <PageHeader title={t("products.new")} />
      </div>
      <Section title={t("products.details")}>
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
      </Section>
      <Section title={t("products.options.title")}>
        <p className="text-sm text-muted-foreground">{t("products.options.explain")}</p>
        <OptionsEditor options={options} onChange={setOptions} />
      </Section>
      <Section title={t("products.pricesAndStock")}>
        <TextField form={form} name="price" label={t("products.price")} inputMode="decimal" />
        <TextField
          form={form}
          name="costPrice"
          label={t("products.cost")}
          hint={t("products.costHint")}
          inputMode="decimal"
        />
        {options.length === 0 && (
          <TextField form={form} name="sku" label={t("products.sku")} hint={t("products.skuHint")} maxLength={64} />
        )}
        <TextField
          form={form}
          name="openingStock"
          label={t("products.openingStock")}
          hint={options.length > 0 ? t("products.openingStockEach") : undefined}
          inputMode="numeric"
        />
      </Section>
      <FormError form={form} />
      <Button type="submit" size="lg" disabled={create.isPending}>
        {t("products.create")}
      </Button>
    </form>
  );
}
