import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronRight, Plus, Search, TriangleAlert, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { SelectBox } from "@/components/form/Field";
import { LoadMore } from "@/components/layout/LoadMore";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCanWrite } from "@/features/billing/status";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { categoriesQuery, flatCategories, lowStockQuery, type ProductSummary, productsQuery, tagsQuery } from "./api";
import { Thumbnail } from "./Thumbnail";

const route = getRouteApi("/_app/products/");

const ON_DARK_FOCUS = "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text";

/** A list of products: a search panel, then cards on phones and a table from 768 px. */
export function ProductListPage() {
  const { t } = useTranslation();
  const business = useBusiness();
  const canWrite = useCanWrite();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const products = useInfiniteQuery(productsQuery(filters));
  const items = products.data?.pages.flatMap((page) => page.items) ?? [];
  const filtered = !!(filters.q || filters.categoryId || filters.tagId);
  const canManage = hasRole(business, "MANAGER") && canWrite;
  const reset = () => void navigate({ search: {}, replace: true });

  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the panel share one dark header; from 768 px each stands alone. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-2.5 max-md:pr-4.5 max-md:pb-8 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-[30px] leading-tight font-bold md:text-[32px]">{t("nav.products")}</h1>
          {canManage && (
            <div className="flex gap-2.5">
              <Button
                asChild
                variant="outline"
                className="h-11 rounded-[10px] border-[1.5px] text-[15px] font-semibold max-md:border-ledger-outline max-md:bg-transparent max-md:text-white md:text-base"
              >
                <Link
                  to="/products/import"
                  className="no-underline max-md:text-white max-md:hover:bg-white/10 max-md:hover:text-white md:text-foreground"
                >
                  {t("imports.fromExcel")}
                </Link>
              </Button>
              <Button asChild className="hidden h-11 rounded-[10px] text-base font-semibold md:inline-flex">
                <Link to="/products/new" className="text-primary-foreground no-underline hover:text-primary-foreground">
                  <Plus aria-hidden="true" strokeWidth={2.5} />
                  {t("products.new")}
                </Link>
              </Button>
            </div>
          )}
        </div>
        <SearchPanel />
      </div>

      <div className="flex flex-col gap-3 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5 md:gap-4">
        <ActiveFilters />
        {products.isSuccess && items.length === 0 && (
          <div className="flex flex-col items-center gap-2.5 rounded-[14px] border bg-card px-4 py-9 text-center md:py-12">
            <p className="text-[17px] font-semibold">{filtered ? t("products.noMatch") : t("products.none")}</p>
            {filtered && (
              <Button
                variant="outline"
                className="h-11 rounded-[10px] border-[1.5px] border-primary px-4 font-semibold text-primary hover:text-primary"
                onClick={reset}
              >
                {t("products.list.reset")}
              </Button>
            )}
          </div>
        )}
        <ul className="flex flex-col gap-2.5 md:hidden">
          {items.map((product) => (
            <li key={product.id}>
              <ProductCard product={product} />
            </li>
          ))}
        </ul>
        {items.length > 0 && <ProductTable products={items} />}
        <LoadMore query={products} />
      </div>
    </div>
  );
}

/** What's typed and picked waits here; the list follows the address, which changes on "search". */
function SearchPanel() {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const { data: categories } = useQuery(categoriesQuery);
  const { data: tags } = useQuery(tagsQuery);
  const { data: lowStock } = useQuery(lowStockQuery);
  const [q, setQ] = useState(filters.q ?? "");
  const [categoryId, setCategoryId] = useState(filters.categoryId ?? "");
  const [tagId, setTagId] = useState(filters.tagId ?? "");
  // The address can change without this panel: a chip removed, the browser's back button, a shared link.
  useEffect(() => {
    setQ(filters.q ?? "");
    setCategoryId(filters.categoryId ?? "");
    setTagId(filters.tagId ?? "");
  }, [filters.q, filters.categoryId, filters.tagId]);
  const status = filters.status ?? "ACTIVE";
  return (
    // biome-ignore lint/a11y/useSemanticElements: a form with this role is how a search with a button is announced.
    <form
      role="search"
      aria-label={t("products.search")}
      className="flex flex-col gap-2.5 md:relative md:gap-3 md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-4.5 md:pr-6 md:pl-14 md:text-white"
      onSubmit={(event) => {
        event.preventDefault();
        void navigate({
          search: {
            q: q.trim() || undefined,
            categoryId: categoryId || undefined,
            tagId: tagId || undefined,
            status: filters.status,
          },
          replace: true,
        });
      }}
    >
      <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
      <div className="relative">
        <Search
          aria-hidden="true"
          className="absolute top-1/2 left-3.5 size-5 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="search"
          aria-label={t("products.search")}
          placeholder={t("products.searchHint")}
          className={cn(
            "h-12.5 rounded-xl border-0 bg-card pl-11 text-base text-foreground md:h-13 md:text-[17px]",
            ON_DARK_FOCUS,
          )}
          value={q}
          onChange={(event) => setQ(event.target.value)}
        />
      </div>
      <div className="flex flex-wrap items-end gap-2.5 md:gap-3">
        <div className="grid min-w-0 flex-[2_1_100%] grid-cols-2 gap-2.5 md:flex-[2_1_340px] md:gap-3 [&_label]:text-sm [&_label]:text-ledger-text [&_select]:rounded-[10px] [&_select]:border-0 [&_select]:text-[15px] [&_select]:text-foreground">
          <SelectBox
            label={t("products.category")}
            value={categoryId}
            onChange={(event) => setCategoryId(event.target.value)}
          >
            <option value="">{t("products.list.allCategories")}</option>
            {flatCategories(categories ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </SelectBox>
          <SelectBox label={t("products.tags")} value={tagId} onChange={(event) => setTagId(event.target.value)}>
            <option value="">{t("products.list.allTags")}</option>
            {tags?.map((tag) => (
              <option key={tag.id} value={tag.id}>
                {tag.name}
              </option>
            ))}
          </SelectBox>
        </div>
        <fieldset className="flex-none">
          <legend className="sr-only md:not-sr-only md:mb-1.5 md:text-sm md:font-semibold md:text-ledger-text">
            {t("products.statusLabel")}
          </legend>
          <div className="flex h-11 overflow-hidden rounded-[10px] border-[1.5px] border-ledger-outline">
            {(["ACTIVE", "ARCHIVED"] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={status === value}
                className={cn(
                  "min-w-17 cursor-pointer px-3 text-[15px] font-semibold",
                  ON_DARK_FOCUS,
                  "focus-visible:-outline-offset-3",
                  status === value ? "bg-card text-ledger" : "text-white hover:bg-white/10",
                )}
                onClick={() =>
                  void navigate({
                    search: { ...filters, status: value === "ACTIVE" ? undefined : value },
                    replace: true,
                  })
                }
              >
                {t(`products.status.${value}`)}
              </button>
            ))}
          </div>
        </fieldset>
        {!!lowStock?.length && (
          <Link
            to="/products/low-stock"
            className={cn(
              "flex h-11 flex-none items-center gap-1.5 text-[15px] font-semibold text-marigold-text hover:text-marigold-text",
              ON_DARK_FOCUS,
            )}
          >
            <TriangleAlert aria-hidden="true" className="size-4.5" />
            {t("products.lowStockCount", { count: format.number(lowStock.length) })}
          </Link>
        )}
        <div className="grid flex-[1_1_100%] grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2 md:ml-auto md:flex md:flex-none">
          <button
            type="button"
            className={cn(
              "h-12 cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text text-base font-semibold text-white hover:bg-white/10 md:h-11 md:px-4 md:text-[15px]",
              ON_DARK_FOCUS,
            )}
            onClick={() => void navigate({ search: {}, replace: true })}
          >
            {t("products.list.reset")}
          </button>
          <button
            type="submit"
            className={cn(
              "flex h-12 cursor-pointer items-center justify-center gap-1.5 rounded-[10px] bg-marigold-text text-base font-bold text-ledger hover:bg-marigold md:h-11 md:px-4.5 md:text-[15px]",
              ON_DARK_FOCUS,
            )}
          >
            <Search aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
            {t("products.list.searchButton")}
          </button>
        </div>
      </div>
    </form>
  );
}

/** What the list is showing, each with a way to take it off. */
function ActiveFilters() {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const { data: categories } = useQuery(categoriesQuery);
  const { data: tags } = useQuery(tagsQuery);
  const category = flatCategories(categories ?? []).find((option) => option.id === filters.categoryId);
  const tag = tags?.find((option) => option.id === filters.tagId);
  const remove = (change: { q?: undefined; categoryId?: undefined; tagId?: undefined }) =>
    void navigate({ search: { ...filters, ...change }, replace: true });
  const chips = [
    filters.q && {
      key: "q",
      text: `"${filters.q}"`,
      onRemove: () => remove({ q: undefined }),
    },
    category && {
      key: "category",
      text: category.name,
      onRemove: () => remove({ categoryId: undefined }),
    },
    tag && {
      key: "tag",
      text: tag.name,
      onRemove: () => remove({ tagId: undefined }),
    },
  ].filter((chip) => !!chip);
  if (chips.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((chip) => (
        <button
          key={chip.key}
          type="button"
          aria-label={t("products.list.removeFilter", { name: chip.text })}
          className="flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full bg-accent py-1 pr-2.5 pl-3 text-sm font-semibold text-accent-foreground hover:bg-[#D5E9DF]"
          onClick={chip.onRemove}
        >
          {chip.text}
          <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
        </button>
      ))}
    </div>
  );
}

function usePriceText() {
  const format = useFormat();
  return ({ minPrice, maxPrice }: ProductSummary) => {
    if (minPrice === null || maxPrice === null) {
      return "";
    }
    return minPrice === maxPrice ? format.taka(minPrice) : `${format.taka(minPrice)} – ${format.taka(maxPrice)}`;
  };
}

function LowStockBadge() {
  const { t } = useTranslation();
  return (
    <span className="inline-block flex-none rounded-full bg-warning px-2 text-xs font-semibold text-warning-foreground">
      {t("products.lowStock")}
    </span>
  );
}

function ProductCard({ product }: { product: ProductSummary }) {
  const { t } = useTranslation();
  const format = useFormat();
  const priceText = usePriceText();
  return (
    <Link
      to="/products/$productId"
      params={{ productId: product.id }}
      className="flex items-center gap-3 rounded-[14px] border bg-card py-2.5 pr-2.5 pl-3 text-foreground no-underline hover:text-foreground"
    >
      <Thumbnail src={product.thumbnail} className="size-16" />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
          <span className="font-bold">{product.name}</span>
          {product.lowStock && <LowStockBadge />}
        </span>
        <span className="text-[13px] text-muted-foreground">
          {t("products.variantCount", { count: format.number(product.activeVariants) })} ·{" "}
          {t("products.available", { count: format.number(product.available) })}
        </span>
        <span className="mt-0.5 text-[15px] font-bold">{priceText(product)}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-4.5 flex-none text-muted-foreground" />
    </Link>
  );
}

function ProductTable({ products }: { products: ProductSummary[] }) {
  const { t } = useTranslation();
  const format = useFormat();
  const priceText = usePriceText();
  const { data: categories } = useQuery(categoriesQuery);
  const categoryNames = new Map(flatCategories(categories ?? []).map((category) => [category.id, category.name]));
  return (
    <section
      aria-label={t("products.list.tableLabel")}
      className="hidden overflow-x-auto rounded-[14px] border bg-card md:block"
    >
      <table className="w-full min-w-200 border-collapse">
        <thead>
          <tr className="border-b bg-background text-left text-[13px] text-muted-foreground">
            <th scope="col" className="py-2.5 pr-3 pl-5 font-semibold">
              {t("products.columns.product")}
            </th>
            <th scope="col" className="px-3 py-2.5 font-semibold">
              {t("products.columns.category")}
            </th>
            <th scope="col" className="px-3 py-2.5 font-semibold">
              {t("products.columns.tags")}
            </th>
            <th scope="col" className="px-3 py-2.5 text-right font-semibold">
              {t("products.detail.available")}
            </th>
            <th scope="col" className="px-3 py-2.5 text-right font-semibold">
              {t("products.columns.price")}
            </th>
            <td className="w-10" />
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.id} className="relative border-b last:border-b-0 hover:bg-background">
              <td className="py-2 pr-3 pl-5">
                {/* The link stretches over the whole row, so anywhere on it opens the product. */}
                <Link
                  to="/products/$productId"
                  params={{ productId: product.id }}
                  className="flex items-center gap-3 text-foreground no-underline after:absolute after:inset-0 hover:text-foreground focus-visible:outline-none focus-visible:after:outline-3 focus-visible:after:-outline-offset-3 focus-visible:after:outline-primary"
                >
                  <Thumbnail src={product.thumbnail} className="size-13" />
                  <span className="flex min-w-0 flex-col">
                    <span className="font-bold">{product.name}</span>
                    <span className="text-[13px] text-muted-foreground">
                      {t("products.variantCount", { count: format.number(product.activeVariants) })}
                    </span>
                  </span>
                </Link>
              </td>
              <td className="px-3 py-2 text-[15px] text-[#2B342F]">
                {product.categoryId && categoryNames.get(product.categoryId)}
              </td>
              <td className="px-3 py-2">
                <span className="flex flex-wrap gap-1">
                  {product.tags.map((tag) => (
                    <span key={tag.id} className="rounded-full bg-muted px-2.25 text-[13px] text-[#2B342F]">
                      {tag.name}
                    </span>
                  ))}
                </span>
              </td>
              <td className="px-3 py-2 text-right">
                <span
                  className={cn(
                    "block text-[17px] font-bold",
                    product.lowStock ? "text-warning-foreground" : "text-primary",
                  )}
                >
                  {format.number(product.available)}
                </span>
                {product.lowStock && <LowStockBadge />}
              </td>
              <td className="px-3 py-2 text-right font-bold whitespace-nowrap">{priceText(product)}</td>
              <td className="pr-4 text-right">
                <ChevronRight aria-hidden="true" className="ml-auto size-4.5 text-muted-foreground" />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
