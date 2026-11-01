import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { Plus, Trash2, TriangleAlert, X } from "lucide-react";
import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka } from "@/lib/money";
import { cn } from "@/lib/utils";
import { categoriesQuery, flatCategories, productChanged } from "./api";
import { BackToProducts } from "./LowStockPage";
import { variantRow } from "./schemas";
import { TagPicker } from "./TagPicker";

const MAX_VARIANTS = 100;
const ROW_COLUMNS =
  "md:grid-cols-[52px_minmax(0,1.5fr)_minmax(0,0.8fr)_minmax(0,0.7fr)_minmax(0,1fr)_minmax(0,1fr)_52px]";
const EMPTY_ROW = { name: "", size: "", quantity: "", costPrice: "", price: "" };

const schema = z.object({
  name: z.string().check(z.trim(), z.minLength(1, "field.required"), z.maxLength(200, "field.nameLong")),
  description: z.string().check(z.trim(), z.maxLength(5000, "field.footerLong")),
  categoryId: z.string(),
  variants: z.array(variantRow),
});

type Row = typeof EMPTY_ROW;
type RowField = keyof Row;
type Note = { text: string; blocks: boolean };

const sameKind = (row: Row) => `${row.name.trim()}|${row.size.trim()}`.toLowerCase();

/** What's wrong with a row beyond its own boxes: its name and size against the other rows. */
function pairProblem(
  rows: Row[],
  index: number,
): "products.variantRows.duplicate" | "products.variantRows.nameNeeded" | null {
  const row = rows[index] as Row;
  if (rows.some((other, i) => i !== index && sameKind(other) === sameKind(row))) {
    return "products.variantRows.duplicate";
  }
  return rows.length > 1 && !row.name.trim() && !row.size.trim() ? "products.variantRows.nameNeeded" : null;
}

const sellsBelowCost = (row: Row) => {
  const cost = row.costPrice.trim() ? parseTaka(row.costPrice) : null;
  const price = row.price.trim() ? parseTaka(row.price) : null;
  return cost !== null && price !== null && price < cost;
};

/** A new product: its details, then one row for each variant it comes in. */
export function NewProductPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: categories } = useQuery(categoriesQuery);
  const [tagIds, setTagIds] = useState<string[]>([]);
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: { name: "", description: "", categoryId: "", variants: [EMPTY_ROW] },
  });
  const { fields, append, remove } = useFieldArray({ control: form.control, name: "variants" });
  const rows = form.watch("variants");
  const tried = form.formState.submitCount > 0;
  const rowErrors = form.formState.errors.variants;
  const total = rows.reduce((sum, row) => sum + (parseCount(row.quantity) ?? 0), 0);

  const create = useMutation({
    mutationFn: (values: z.output<typeof schema>) =>
      call(
        api.POST("/api/v1/products", {
          body: {
            name: values.name,
            description: values.description || undefined,
            categoryId: values.categoryId || undefined,
            tagIds,
            variants: values.variants,
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: async (product) => {
      productChanged(queryClient, product);
      await navigate({ to: "/products/$productId", params: { productId: product.id }, replace: true });
    },
    onError: (error) => showServerError(form, error),
  });

  /** A new row starts like the one above, since a colour usually comes in several sizes at one price. */
  const addRow = () => {
    const last = rows[rows.length - 1] ?? EMPTY_ROW;
    append(
      { ...EMPTY_ROW, name: last.name, costPrice: last.costPrice, price: last.price },
      { focusName: `variants.${rows.length}.size` },
    );
  };

  const noteFor = (index: number): Note | null => {
    const row = rows[index] as Row;
    const errors = rowErrors?.[index];
    const invalid = errors?.price ?? errors?.quantity ?? errors?.costPrice ?? errors?.name ?? errors?.size;
    if (invalid?.message) {
      return { text: t(invalid.message as never), blocks: true };
    }
    const pair = pairProblem(rows, index);
    if (pair && (tried || pair === "products.variantRows.duplicate")) {
      return { text: t(pair), blocks: true };
    }
    return sellsBelowCost(row) ? { text: t("products.variantRows.belowCost"), blocks: false } : null;
  };
  const showFix = tried && rows.some((_, index) => noteFor(index)?.blocks);

  return (
    <form
      noValidate
      className="flex w-full flex-col gap-4.5"
      onSubmit={form.handleSubmit((values) => {
        if (!rows.some((_, index) => pairProblem(rows, index))) {
          create.mutate(values);
        }
      })}
    >
      {/* On phones the title sits on the dark header, as on Today. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:bg-ledger max-md:pt-2 max-md:pr-4.5 max-md:pb-8 max-md:pl-6.5 max-md:text-white">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">
          <BackToProducts />
        </div>
        <h1 className="text-[30px] leading-tight font-bold md:text-[32px]">{t("products.new")}</h1>
      </div>

      <div className="flex flex-col gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-8 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-4">
        <section
          aria-labelledby="details"
          className="flex flex-col gap-3.5 rounded-[14px] border bg-card p-4 md:px-5 md:pt-4.5 md:pb-5"
        >
          <h2 id="details" className="text-[19px] font-bold">
            {t("products.details")}
          </h2>
          <div className="grid gap-x-6 gap-y-3.5 lg:grid-cols-2">
            <div className="flex min-w-0 flex-col gap-3.5">
              <TextField form={form} name="name" label={t("products.name")} />
              <TextAreaField form={form} name="description" label={t("products.description")} />
            </div>
            <div className="flex min-w-0 flex-col gap-3.5">
              <SelectField form={form} name="categoryId" label={t("products.category")}>
                <option value="">{t("products.noCategory")}</option>
                {flatCategories(categories ?? []).map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </SelectField>
              <TagPicker selected={tagIds} onChange={setTagIds} />
            </div>
          </div>
        </section>

        <section aria-labelledby="variants" className="overflow-hidden rounded-[14px] border bg-card">
          <div className="flex flex-wrap items-center justify-between gap-x-5 gap-y-1 bg-ledger px-4 py-3.5 text-white md:px-5 md:py-4">
            <div>
              <h2 id="variants" className="text-[19px] font-bold">
                {t("products.variants")}
              </h2>
              <p className="text-sm text-ledger-text">{t("products.variantRows.subtitle")}</p>
            </div>
            <p role="status" className="text-[15px] font-semibold text-marigold-text">
              {t("products.variantRows.summary", {
                count: rows.length,
                variants: format.number(rows.length),
                total: format.number(total),
              })}
            </p>
          </div>

          <div
            aria-hidden="true"
            className={cn(
              "hidden gap-2.5 border-b pt-2.5 pr-2 pb-2 text-[13px] font-semibold text-muted-foreground md:grid",
              ROW_COLUMNS,
            )}
          >
            <span />
            <span className="pl-2">{t("products.variantRows.nameColumn")}</span>
            <span>{t("products.variantRows.size")}</span>
            <span>{t("products.variantRows.quantity")}</span>
            <span>{t("products.variantRows.cost")}</span>
            <span>{t("products.variantRows.price")}</span>
            <span />
          </div>

          <div className="relative">
            <div aria-hidden="true" className="absolute inset-y-0 left-[51px] hidden w-0.5 bg-marigold md:block" />
            {fields.map((field, index) => {
              const number = format.number(index + 1);
              const note = noteFor(index);
              const noteId = `variant-note-${field.id}`;
              const errors = rowErrors?.[index];
              const pairWrong = note?.blocks && !errors && !!pairProblem(rows, index);
              const box = (
                name: RowField,
                label: string,
                options: { wrong?: boolean; className?: string; hint?: string; numeric?: "numeric" | "decimal" },
              ) => (
                <div className={cn("flex min-w-0 flex-col justify-end gap-1.5", options.className)}>
                  <label htmlFor={`${field.id}-${name}`} className="text-sm font-semibold md:sr-only">
                    <span className="sr-only">{t("products.variantRows.number", { number })}: </span>
                    {label}
                  </label>
                  <Input
                    id={`${field.id}-${name}`}
                    inputMode={options.numeric}
                    placeholder={options.hint}
                    aria-invalid={options.wrong || !!errors?.[name] || undefined}
                    aria-describedby={note ? noteId : undefined}
                    className="h-11 rounded-[10px] border-[1.5px] px-3 text-base"
                    {...form.register(`variants.${index}.${name}`)}
                  />
                </div>
              );
              return (
                <div key={field.id} className="border-b last:border-b-0 md:border-b-0">
                  <div
                    className={cn(
                      "relative grid grid-cols-6 gap-2.5 px-4 py-3 md:items-center md:py-2 md:pr-2 md:pl-0",
                      ROW_COLUMNS,
                    )}
                  >
                    <p className="col-span-6 flex min-h-8 items-center text-[15px] font-bold text-warning-foreground md:col-span-1 md:justify-center">
                      <span className="md:hidden">{t("products.variantRows.number", { number })}</span>
                      <span className="max-md:hidden">{number}</span>
                    </p>
                    {box("name", t("products.variantRows.name"), {
                      wrong: pairWrong,
                      hint: t("products.variantRows.nameHint"),
                      className: "col-span-4 md:col-span-1 md:pl-2",
                    })}
                    {box("size", t("products.variantRows.size"), {
                      wrong: pairWrong,
                      hint: t("products.variantRows.sizeHint"),
                      className: "col-span-2 md:col-span-1",
                    })}
                    {box("quantity", t("products.variantRows.quantity"), {
                      numeric: "numeric",
                      className: "col-span-2 md:col-span-1",
                    })}
                    {box("costPrice", t("products.variantRows.cost"), {
                      numeric: "decimal",
                      className: "col-span-2 md:col-span-1",
                    })}
                    {box("price", t("products.variantRows.price"), {
                      numeric: "decimal",
                      className: "col-span-2 md:col-span-1",
                    })}
                    {fields.length > 1 && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        aria-label={t("products.variantRows.remove", { number })}
                        className="absolute top-1.5 right-2 size-11 text-muted-foreground hover:bg-danger-soft hover:text-danger-ink md:static"
                        onClick={() => remove(index)}
                      >
                        <Trash2 aria-hidden="true" className="size-5" />
                      </Button>
                    )}
                  </div>
                  {note && (
                    <p
                      id={noteId}
                      className={cn(
                        "flex items-start gap-1.5 px-4 pb-3 text-sm font-semibold md:-mt-0.5 md:pb-2 md:pl-[60px]",
                        note.blocks ? "text-destructive" : "text-warning-foreground",
                      )}
                    >
                      {note.blocks ? (
                        <X aria-hidden="true" className="mt-0.5 size-4 flex-none" strokeWidth={2.5} />
                      ) : (
                        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 flex-none" />
                      )}
                      {note.text}
                    </p>
                  )}
                </div>
              );
            })}
          </div>

          <div className="flex flex-col gap-2.5 border-t px-4 pt-3 pb-4 md:flex-row md:flex-wrap md:items-center md:justify-between md:gap-x-5 md:px-5">
            {fields.length < MAX_VARIANTS ? (
              <button
                type="button"
                onClick={addRow}
                className="flex min-h-11 cursor-pointer items-center justify-center gap-1.5 rounded-[10px] border-[1.5px] border-dashed border-primary bg-card px-4 font-semibold text-primary hover:bg-accent"
              >
                <Plus aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
                {t("products.variantRows.add")}
              </button>
            ) : (
              <p className="text-sm font-semibold">{t("products.variantRows.tooMany")}</p>
            )}
            <p className="text-sm text-muted-foreground">{t("products.variantRows.note")}</p>
          </div>
        </section>

        <FormError form={form} strong />
        {showFix && (
          <p
            role="alert"
            className="flex items-start gap-2 rounded-[10px] bg-danger-soft px-3.5 py-3 text-[15px] font-semibold text-danger-ink"
          >
            <X aria-hidden="true" className="mt-0.5 size-4.5 flex-none" strokeWidth={2.5} />
            {t("products.variantRows.fix")}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2.5 md:flex-row md:justify-end">
          <Button variant="outline" size="lg" className="h-12 rounded-[10px] font-semibold" asChild>
            <Link to="/products" className="text-foreground no-underline hover:text-foreground">
              {t("products.variantRows.cancel")}
            </Link>
          </Button>
          <Button
            type="submit"
            size="lg"
            className="h-12 rounded-[10px] px-6 font-semibold"
            disabled={create.isPending}
          >
            {t("products.create")}
          </Button>
        </div>
      </div>
    </form>
  );
}
