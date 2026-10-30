import { infiniteQueryOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";

export type ProductSummary = components["schemas"]["ProductSummary"];
export type Product = components["schemas"]["ProductDetail"];
export type Variant = components["schemas"]["ProductVariant"];
export type CategoryNode = components["schemas"]["CategoryNode"];
export type Tag = components["schemas"]["TagResponse"];
export type ProductStatus = Product["status"];

export interface ProductFilters {
  q?: string;
  categoryId?: string;
  tagId?: string;
  status?: ProductStatus;
}

export const productsQuery = (filters: ProductFilters) =>
  infiniteQueryOptions({
    queryKey: ["products", filters],
    queryFn: ({ pageParam }) =>
      call(api.GET("/api/v1/products", { params: { query: { ...filters, cursor: pageParam } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
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

export const lowStockQuery = queryOptions({
  queryKey: ["low-stock"],
  queryFn: () => call(api.GET("/api/v1/stock/low")),
});

/** The answer to a change to one product: its page has it at once, and the lists read again. */
export function productChanged(queryClient: QueryClient, product: Product) {
  queryClient.setQueryData(productQuery(product.id).queryKey, product);
  void queryClient.invalidateQueries({ queryKey: ["products"] });
  void queryClient.invalidateQueries(lowStockQuery);
}

/** Each top-level category followed by its subcategories, for pickers: "Women › Kurti". */
export function flatCategories(tree: CategoryNode[]): { id: string; name: string }[] {
  return tree.flatMap((top) => [
    { id: top.id, name: top.name },
    ...top.children.map((child) => ({ id: child.id, name: `${top.name} › ${child.name}` })),
  ]);
}
