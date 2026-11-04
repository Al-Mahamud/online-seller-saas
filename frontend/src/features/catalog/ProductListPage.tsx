import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Archive, ChevronRight, LayoutGrid, Plus, Tag, TriangleAlert } from "lucide-react";
import { useTranslation } from "react-i18next";
import { can, useBusiness } from "@/api/business";
import { AddressPagination } from "@/components/layout/Pagination";
import { type ChoiceFilter, ListSearch } from "@/components/search/ListSearch";
import { Button } from "@/components/ui/button";
import { useCanWrite } from "@/features/billing/status";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { categoriesQuery, flatCategories, lowStockQuery, type ProductSummary, productsQuery, tagsQuery } from "./api";
import { LowStockLink } from "./LowStockWindow";
import { Thumbnail } from "./Thumbnail";

const route = getRouteApi("/_app/products/");

/** A list of products: a search panel, then cards on phones and a table from 768 px. */
export function ProductListPage() {
  const { t } = useTranslation();
  const business = useBusiness();
  const canWrite = useCanWrite();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const products = useQuery(productsQuery(filters));
  const items = products.data?.items ?? [];
  const filtered = !!(filters.q || filters.categoryId || filters.tagId);
  const canManage = can(business, "PRODUCTS") && canWrite;
  const reset = () => void navigate({ search: { size: filters.size }, replace: true });

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
        <AddressPagination result={products.data} />
      </div>
    </div>
  );
}

/** The search with category, tag and archive filters; how many there are, and a way to what's running low. */
function SearchPanel() {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const { data: categories } = useQuery(categoriesQuery);
  const { data: tags } = useQuery(tagsQuery);
  const { data: lowStock } = useQuery({ ...lowStockQuery({ size: 1 }), enabled: can(useBusiness(), "STOCK") });
  const products = useQuery(productsQuery(filters));
  const choices: ChoiceFilter[] = [
    {
      key: "categoryId",
      label: t("products.category"),
      icon: LayoutGrid,
      allLabel: t("products.list.allCategories"),
      options: flatCategories(categories ?? []).map((category) => ({ value: category.id, label: category.name })),
    },
    {
      key: "tagId",
      label: t("products.tags"),
      icon: Tag,
      allLabel: t("products.list.allTags"),
      options: (tags ?? []).map((tag) => ({ value: tag.id, label: tag.name })),
    },
    {
      key: "status",
      label: t("products.statusLabel"),
      icon: Archive,
      defaultValue: "ACTIVE",
      options: (["ACTIVE", "ARCHIVED"] as const).map((status) => ({
        value: status,
        label: t(`products.status.${status}`),
      })),
    },
  ];
  return (
    <ListSearch
      list="products"
      label={t("products.search")}
      hint={t("products.searchHint")}
      value={{ q: filters.q, categoryId: filters.categoryId, tagId: filters.tagId, status: filters.status }}
      choices={choices}
      count={
        products.data
          ? t("products.list.found", {
              count: products.data.totalItems,
              total: format.number(products.data.totalItems),
            })
          : undefined
      }
      aside={
        !!lowStock?.totalItems && (
          <LowStockLink className="flex min-h-9 items-center gap-1.5 rounded-full bg-warning px-3 text-sm font-semibold text-warning-foreground no-underline hover:text-warning-foreground">
            <TriangleAlert aria-hidden="true" className="size-4" />
            {t("products.lowStockCount", { count: format.number(lowStock.totalItems) })}
          </LowStockLink>
        )
      }
      onChange={(value) =>
        void navigate({
          search: {
            q: value.q,
            categoryId: value.categoryId,
            tagId: value.tagId,
            status: value.status === "ARCHIVED" ? "ARCHIVED" : undefined,
            size: filters.size,
          },
          replace: true,
        })
      }
      onReset={() => void navigate({ search: { size: filters.size }, replace: true })}
    />
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
