import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Plus, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { categoriesQuery, flatCategories, lowStockQuery, type ProductSummary, productsQuery, tagsQuery } from "./api";

const route = getRouteApi("/_app/products/");

/** A list of products, as in the mockup: cards on phones, a table from 768 px. */
export function ProductListPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const products = useInfiniteQuery(productsQuery(filters));
  const { data: lowStock } = useQuery(lowStockQuery);
  const items = products.data?.pages.flatMap((page) => page.items) ?? [];
  const filtered = !!(filters.q || filters.categoryId || filters.tagId);

  // Loads the next page as its end comes into view.
  const end = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = products;
  useEffect(() => {
    const element = end.current;
    if (!element || !hasNextPage) {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
        void fetchNextPage();
      }
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={t("nav.products")}>
        {hasRole(business, "MANAGER") && (
          <div className="flex gap-2">
            <Button asChild variant="outline">
              <Link to="/products/import">{t("imports.fromExcel")}</Link>
            </Button>
            <Button asChild className="hidden md:inline-flex">
              <Link to="/products/new">
                <Plus aria-hidden="true" />
                {t("products.new")}
              </Link>
            </Button>
          </div>
        )}
      </PageHeader>
      <SearchBox
        value={filters.q ?? ""}
        onChange={(q) => void navigate({ search: { ...filters, q: q || undefined }, replace: true })}
      />
      <FilterChips />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <StatusToggle />
        {!!lowStock?.length && (
          <Link
            to="/products/low-stock"
            className="flex min-h-11 items-center font-medium text-warning-foreground underline"
          >
            {t("products.lowStockCount", { count: format.number(lowStock.length) })}
          </Link>
        )}
      </div>

      {products.isSuccess && items.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">
          {filtered ? t("products.noMatch") : t("products.none")}
        </p>
      )}
      <ul className="flex flex-col gap-2.5 md:hidden">
        {items.map((product) => (
          <li key={product.id}>
            <ProductCard product={product} />
          </li>
        ))}
      </ul>
      {items.length > 0 && <ProductTable products={items} />}
      {hasNextPage && (
        <div ref={end} className="flex justify-center py-2">
          <Button variant="outline" disabled={isFetchingNextPage} onClick={() => void fetchNextPage()}>
            {t("audit.more")}
          </Button>
        </div>
      )}
    </div>
  );
}

function SearchBox({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState(value);

  // The address follows the typing after a pause, and each pause is one search.
  useEffect(() => {
    if (text === value) {
      return;
    }
    const timer = setTimeout(() => onChange(text.trim()), 300);
    return () => clearTimeout(timer);
  }, [text, value, onChange]);

  return (
    <div className="relative">
      <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-muted-foreground" />
      <Input
        type="search"
        aria-label={t("products.search")}
        placeholder={t("products.searchHint")}
        className="pl-9.5"
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
    </div>
  );
}

function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={cn(
        "h-9 flex-none cursor-pointer rounded-full border px-3.5 text-sm",
        selected ? "border-foreground bg-foreground text-background" : "border-input bg-card text-foreground",
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/** One category or tag at a time. A top-level category also finds what's in its subcategories. */
function FilterChips() {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const { data: categories } = useQuery(categoriesQuery);
  const { data: tags } = useQuery(tagsQuery);
  const choose = (choice: { categoryId?: string; tagId?: string }) =>
    void navigate({ search: { q: filters.q, status: filters.status, ...choice }, replace: true });
  const selectedTop = categories?.find(
    (top) => top.id === filters.categoryId || top.children.some((child) => child.id === filters.categoryId),
  );
  return (
    <>
      <div className="-mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0">
        <Chip selected={!filters.categoryId && !filters.tagId} onClick={() => choose({})}>
          {t("products.all")}
        </Chip>
        {categories?.map((top) => (
          <Chip key={top.id} selected={selectedTop?.id === top.id} onClick={() => choose({ categoryId: top.id })}>
            {top.name}
          </Chip>
        ))}
        {tags?.map((tag) => (
          <Chip key={tag.id} selected={filters.tagId === tag.id} onClick={() => choose({ tagId: tag.id })}>
            #{tag.name}
          </Chip>
        ))}
      </div>
      {!!selectedTop?.children.length && (
        <div className="-mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0">
          {selectedTop.children.map((child) => (
            <Chip
              key={child.id}
              selected={filters.categoryId === child.id}
              onClick={() => choose({ categoryId: child.id })}
            >
              {child.name}
            </Chip>
          ))}
        </div>
      )}
    </>
  );
}

function StatusToggle() {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const status = filters.status ?? "ACTIVE";
  return (
    <fieldset
      aria-label={t("products.statusLabel")}
      className="flex w-fit overflow-hidden rounded-lg border border-input"
    >
      {(["ACTIVE", "ARCHIVED"] as const).map((value) => (
        <button
          key={value}
          type="button"
          aria-pressed={status === value}
          className={cn(
            "min-h-11 cursor-pointer px-4",
            status === value ? "bg-foreground font-semibold text-background" : "bg-card text-foreground",
          )}
          onClick={() =>
            void navigate({ search: { ...filters, status: value === "ACTIVE" ? undefined : value }, replace: true })
          }
        >
          {t(`products.status.${value}`)}
        </button>
      ))}
    </fieldset>
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

function Thumbnail({ src, className }: { src: string | null; className: string }) {
  const { t } = useTranslation();
  return src ? (
    <img src={src} alt="" loading="lazy" className={cn("flex-none rounded-lg object-cover", className)} />
  ) : (
    <span
      aria-hidden="true"
      className={cn(
        "flex flex-none items-center justify-center rounded-lg bg-muted text-xs text-muted-foreground",
        className,
      )}
    >
      {t("products.noImage")}
    </span>
  );
}

function LowStockBadge() {
  const { t } = useTranslation();
  return (
    <span className="flex-none rounded-full bg-warning px-2 text-xs font-semibold text-warning-foreground">
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
      className="flex items-center gap-3 rounded-xl border bg-card p-2.5 text-foreground no-underline"
    >
      <Thumbnail src={product.thumbnail} className="size-16" />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate font-semibold">{product.name}</span>
        <span className="text-sm text-muted-foreground">
          {t("products.variantCount", { count: format.number(product.activeVariants) })} ·{" "}
          {t("products.available", { count: format.number(product.available) })}
        </span>
        <span className="font-bold">{priceText(product)}</span>
      </span>
      {product.lowStock && <LowStockBadge />}
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
    <div className="hidden overflow-x-auto rounded-xl border bg-card md:block">
      <table className="w-full min-w-190 border-collapse">
        <thead>
          <tr className="border-b text-left text-[13px] text-muted-foreground">
            <th scope="col" className="px-4.5 py-3.5 font-semibold">
              {t("products.columns.product")}
            </th>
            <th scope="col" className="px-3 py-3.5 font-semibold">
              {t("products.columns.category")}
            </th>
            <th scope="col" className="px-3 py-3.5 font-semibold">
              {t("products.columns.tags")}
            </th>
            <th scope="col" className="px-3 py-3.5 font-semibold">
              {t("products.columns.stock")}
            </th>
            <th scope="col" className="px-4.5 py-3.5 text-right font-semibold">
              {t("products.columns.price")}
            </th>
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.id} className="border-b last:border-b-0">
              <td className="px-4.5 py-3">
                <Link
                  to="/products/$productId"
                  params={{ productId: product.id }}
                  className="flex items-center gap-3.5 text-foreground no-underline"
                >
                  <Thumbnail src={product.thumbnail} className="size-13" />
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-semibold">{product.name}</span>
                    <span className="text-[13px] text-muted-foreground">
                      {t("products.variantCount", { count: format.number(product.activeVariants) })}
                    </span>
                  </span>
                </Link>
              </td>
              <td className="px-3 py-3 text-[#2B342F]">
                {product.categoryId && categoryNames.get(product.categoryId)}
              </td>
              <td className="px-3 py-3">
                <span className="flex flex-wrap gap-1.5">
                  {product.tags.map((tag) => (
                    <span key={tag.id} className="rounded-full bg-muted px-2.5 text-[13px] text-[#2B342F]">
                      {tag.name}
                    </span>
                  ))}
                </span>
              </td>
              <td className="px-3 py-3">
                <span className="flex items-center gap-2">
                  {format.number(product.available)}
                  {product.lowStock && <LowStockBadge />}
                </span>
              </td>
              <td className="px-4.5 py-3 text-right font-semibold whitespace-nowrap">{priceText(product)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
