import { infiniteQueryOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { nextPage, type Paging, pagedQuery } from "@/lib/paging";

export type ProductSummary = components["schemas"]["ProductSummary"];
export type Product = components["schemas"]["ProductDetail"];
export type Variant = components["schemas"]["ProductVariant"];
export type CategoryNode = components["schemas"]["CategoryNode"];
export type Tag = components["schemas"]["TagResponse"];
export type ProductStatus = Product["status"];

export interface ProductFilters extends Paging {
  q?: string;
  categoryId?: string;
  tagId?: string;
  status?: ProductStatus;
}

export const productsQuery = (filters: ProductFilters) =>
  pagedQuery(["products", filters], () => call(api.GET("/api/v1/products", { params: { query: filters } })));

/** Products on sale, for a picker that reads on as it scrolls. */
export const productPickerQuery = (q: string) =>
  infiniteQueryOptions({
    queryKey: ["products", "picker", q],
    queryFn: ({ pageParam }) =>
      call(
        api.GET("/api/v1/products", { params: { query: { q: q || undefined, status: "ACTIVE", page: pageParam } } }),
      ),
    initialPageParam: 1,
    getNextPageParam: nextPage,
  });

export const productQuery = (id: string) =>
  queryOptions({
    queryKey: ["product", id],
    queryFn: () => call(api.GET("/api/v1/products/{id}", { params: { path: { id } } })),
  });

export const categoriesQuery = queryOptions({
  queryKey: ["categories"],
  queryFn: () => call(api.GET("/api/v1/categories")),
});

export const tagsQuery = queryOptions({ queryKey: ["tags"], queryFn: () => call(api.GET("/api/v1/tags")) });

export const lowStockQuery = (paging: Paging) =>
  pagedQuery(["low-stock", paging], () => call(api.GET("/api/v1/stock/low", { params: { query: paging } })));

/** The answer to a change to one product: its page has it at once, and the lists read again. */
export function productChanged(queryClient: QueryClient, product: Product) {
  queryClient.setQueryData(productQuery(product.id).queryKey, product);
  void queryClient.invalidateQueries({ queryKey: ["products"] });
  void queryClient.invalidateQueries({ queryKey: ["low-stock"] });
}

/** Each top-level category followed by its subcategories, for pickers: "Women › Kurti". */
export function flatCategories(tree: CategoryNode[]): { id: string; name: string }[] {
  return tree.flatMap((top) => [
    { id: top.id, name: top.name },
    ...top.children.map((child) => ({ id: child.id, name: `${top.name} › ${child.name}` })),
  ]);
}
