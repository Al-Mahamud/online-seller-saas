import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { SearchBox } from "@/components/layout/SearchBox";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { type Product, type ProductSummary, productQuery, type Variant } from "@/features/catalog/api";
import { Thumbnail } from "@/features/catalog/Thumbnail";
import { useFormat } from "@/lib/format";

export interface PickedItem {
  variantId: string;
  name: string;
  label: string;
  available: number;
  price: number;
}

function Available({ count }: { count: number }) {
  const { t } = useTranslation();
  const format = useFormat();
  return count > 0 ? (
    <span className="text-sm text-muted-foreground">{t("products.available", { count: format.number(count) })}</span>
  ) : (
    <span className="text-sm font-semibold text-warning-foreground">{t("orders.outOfStock")}</span>
  );
}

/** Search products, with what is available now; a product with variants asks which one. */
export function ItemPicker({ onPick }: { onPick: (item: PickedItem) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  // Each added item clears the box, ready for the next.
  const [round, setRound] = useState(0);
  const [choosing, setChoosing] = useState<Product>();
  const [opening, setOpening] = useState<string>();
  const results = useQuery({
    queryKey: ["products", "picker", search],
    queryFn: () => call(api.GET("/api/v1/products", { params: { query: { q: search, limit: 8 } } })),
    enabled: search !== "",
  });

  function picked(product: Product, variant: Variant) {
    onPick({
      variantId: variant.id,
      name: product.name,
      label: variant.label,
      available: variant.available,
      price: variant.price,
    });
    setChoosing(undefined);
    setSearch("");
    setRound(round + 1);
  }

  async function open(summary: ProductSummary) {
    setOpening(summary.id);
    try {
      const product = await queryClient.fetchQuery({ ...productQuery(summary.id), staleTime: 0 });
      const active = product.variants.filter((variant) => variant.status === "ACTIVE");
      const [only] = active;
      if (active.length === 1 && only) {
        picked(product, only);
      } else {
        setChoosing(product);
      }
    } catch (error) {
      toast.error(errorText(error));
    } finally {
      setOpening(undefined);
    }
  }

  const items = results.data?.items ?? [];
  return (
    <div className="flex flex-col gap-2">
      <SearchBox
        key={round}
        label={t("orders.searchProducts")}
        hint={t("orders.searchProductsHint")}
        value={search}
        onChange={setSearch}
      />
      {results.isSuccess && items.length === 0 && (
        <p className="py-2 text-muted-foreground">{t("orders.noProductMatch")}</p>
      )}
      {items.length > 0 && (
        <ul className="flex flex-col rounded-lg border">
          {items.map((product) => (
            <li key={product.id} className="border-t first:border-t-0">
              <button
                type="button"
                disabled={opening === product.id}
                className="flex min-h-11 w-full cursor-pointer items-center gap-3 p-2 text-left"
                onClick={() => void open(product)}
              >
                <Thumbnail src={product.thumbnail} className="size-11" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{product.name}</span>
                  <Available count={product.available} />
                </span>
                {product.minPrice !== null && (
                  <span className="flex-none font-semibold">{format.taka(product.minPrice)}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
      {choosing && (
        <Dialog open onOpenChange={(open) => !open && setChoosing(undefined)}>
          <DialogContent className="flex max-h-dvh flex-col">
            <DialogHeader>
              <DialogTitle>{choosing.name}</DialogTitle>
            </DialogHeader>
            <ul className="min-h-0 flex-1 overflow-y-auto">
              {choosing.variants
                .filter((variant) => variant.status === "ACTIVE")
                .map((variant) => (
                  <li key={variant.id} className="border-b last:border-b-0">
                    <button
                      type="button"
                      className="flex min-h-11 w-full cursor-pointer items-center justify-between gap-3 py-2 text-left"
                      onClick={() => picked(choosing, variant)}
                    >
                      <span className="min-w-0">
                        <span className="block font-semibold">{variant.label || choosing.name}</span>
                        <Available count={variant.available} />
                      </span>
                      <span className="flex-none font-semibold">{format.taka(variant.price)}</span>
                    </button>
                  </li>
                ))}
            </ul>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
