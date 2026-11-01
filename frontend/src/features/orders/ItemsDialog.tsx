import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, X } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { LoadMore } from "@/components/layout/LoadMore";
import { SearchBox } from "@/components/layout/SearchBox";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { type Product, type ProductSummary, productQuery, productsQuery, type Variant } from "@/features/catalog/api";
import { Thumbnail } from "@/features/catalog/Thumbnail";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";

export interface PickedItem {
  productId: string;
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

/**
 * Products on one side and the chosen product's variants on the other; on phones, one after the
 * other. What's ticked is kept while moving between products, and only OK adds it to the order.
 */
export default function ItemsDialog({
  taken,
  onDone,
  onClose,
}: {
  /** Variants already on the order, whose quantity is changed there. */
  taken: string[];
  onDone: (items: PickedItem[]) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [search, setSearch] = useState("");
  const [activeId, setActiveId] = useState<string>();
  const [picked, setPicked] = useState<PickedItem[]>([]);
  const products = useInfiniteQuery(productsQuery({ q: search || undefined, status: "ACTIVE" }));
  // Read again on opening: the stock shown has to be what's there now.
  const active = useQuery({ ...productQuery(activeId ?? ""), enabled: !!activeId, staleTime: 0 });
  const product = activeId ? active.data : undefined;
  const items = products.data?.pages.flatMap((page) => page.items) ?? [];

  function toggle(from: Product, variant: Variant) {
    setPicked((current) =>
      current.some((item) => item.variantId === variant.id)
        ? current.filter((item) => item.variantId !== variant.id)
        : [
            ...current,
            {
              productId: from.id,
              variantId: variant.id,
              name: from.name,
              label: variant.label,
              available: variant.available,
              price: variant.price,
            },
          ],
    );
  }

  const titleText = t("orders.picker.title");
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        aria-describedby={undefined}
        className="flex max-h-[calc(100dvh-2rem)] flex-col gap-0 overflow-hidden p-0 text-base max-md:h-dvh max-md:max-h-dvh max-md:max-w-full max-md:rounded-none sm:max-w-[980px] md:h-[672px] md:w-[calc(100%-2rem)]"
        onInteractOutside={(event) => event.preventDefault()}
        onOpenAutoFocus={(event) => {
          // Straight to searching with a keyboard; a phone's would cover half the list.
          if (window.matchMedia("(min-width: 768px)").matches) {
            event.preventDefault();
            (event.currentTarget as HTMLElement).querySelector("input")?.focus();
          }
        }}
      >
        <div className="flex min-h-15 flex-none items-center gap-1 border-b py-1.5 pr-1.5 pl-2 md:pr-3 md:pl-6">
          {activeId && (
            <Button
              variant="ghost"
              size="icon"
              className="size-11 flex-none text-primary md:hidden"
              aria-label={t("orders.picker.back")}
              onClick={() => setActiveId(undefined)}
            >
              <ChevronLeft aria-hidden="true" className="size-6" />
            </Button>
          )}
          <DialogTitle className="min-w-0 flex-1 pl-2 text-xl font-bold md:pl-0 md:text-[22px]">
            <span className={cn(activeId && "max-md:hidden")}>{titleText}</span>
            {activeId && <span className="md:hidden">{product?.name}</span>}
          </DialogTitle>
          <DialogClose asChild>
            <Button variant="ghost" size="icon" className="size-11 flex-none" aria-label={t("page.close")}>
              <X aria-hidden="true" className="size-5.5" />
            </Button>
          </DialogClose>
        </div>

        <div className="flex min-h-0 flex-1 flex-col md:grid md:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
          <div className={cn("flex min-h-0 flex-1 flex-col md:border-r", activeId && "max-md:hidden")}>
            <div className="flex-none px-3.5 pt-3 pb-2 md:px-4 md:pt-3.5 md:pb-2.5">
              <SearchBox
                label={t("orders.searchProducts")}
                hint={t("orders.searchProductsHint")}
                value={search}
                onChange={setSearch}
              />
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto px-1.5 pb-2 md:px-2">
              {products.isSuccess && items.length === 0 && (
                <p className="p-4 text-center text-muted-foreground">
                  {search ? t("orders.noProductMatch") : t("products.none")}
                </p>
              )}
              <ul className="flex flex-col">
                {items.map((summary) => (
                  <ProductRow
                    key={summary.id}
                    summary={summary}
                    active={summary.id === activeId}
                    count={picked.filter((item) => item.productId === summary.id).length}
                    onOpen={() => setActiveId(summary.id)}
                  />
                ))}
              </ul>
              <LoadMore query={products} />
            </div>
          </div>

          <div className={cn("flex min-h-0 flex-1 flex-col", !activeId && "max-md:hidden")}>
            {!activeId && <p className="m-auto p-6 text-center text-muted-foreground">{t("orders.picker.prompt")}</p>}
            {product && (
              <>
                <div className="flex-none px-3.5 pt-3 pb-2 md:px-5 md:pt-4 md:pb-2.5">
                  <h3 className="font-bold max-md:sr-only md:text-[19px]">{product.name}</h3>
                  <p className="text-sm text-muted-foreground">{t("orders.picker.chooseVariants")}</p>
                </div>
                <ul className="flex min-h-0 flex-1 flex-col gap-1.5 overflow-y-auto px-3.5 pb-3 md:px-3">
                  {product.variants
                    .filter((variant) => variant.status === "ACTIVE")
                    .map((variant) => {
                      const added = taken.includes(variant.id);
                      const on = added || picked.some((item) => item.variantId === variant.id);
                      return (
                        <li key={variant.id}>
                          <label
                            className={cn(
                              "flex min-h-16 items-center gap-3 rounded-[10px] border-[1.5px] px-2.5 py-2",
                              added ? "bg-muted" : "cursor-pointer",
                              !added && on && "border-primary bg-accent",
                            )}
                          >
                            <input
                              type="checkbox"
                              className="size-5.5 flex-none accent-primary"
                              checked={on}
                              disabled={added}
                              onChange={() => toggle(product, variant)}
                            />
                            <span className="min-w-0 flex-1">
                              <span className="block font-semibold">
                                {variant.label || t("orders.picker.onlyVariant")}
                              </span>
                              {added ? (
                                <span className="text-sm text-muted-foreground">{t("orders.picker.added")}</span>
                              ) : (
                                <Available count={variant.available} />
                              )}
                            </span>
                            <span className="flex-none font-bold">{format.taka(variant.price)}</span>
                          </label>
                        </li>
                      );
                    })}
                </ul>
              </>
            )}
          </div>
        </div>

        <div className="flex flex-none flex-col gap-2 border-t px-3.5 pt-2.5 pb-[calc(14px+env(safe-area-inset-bottom))] md:flex-row md:items-center md:justify-end md:gap-2.5 md:px-5 md:py-3">
          <span role="status" className="text-[15px] font-semibold md:mr-auto md:text-base">
            {picked.length > 0
              ? t("orders.selected", { count: format.number(picked.length) })
              : t("orders.picker.nonePicked")}
          </span>
          <DialogClose asChild>
            <Button
              variant="outline"
              className="h-11.5 rounded-[10px] border-[1.5px] px-4.5 text-base font-semibold max-md:hidden"
            >
              {t("page.cancel")}
            </Button>
          </DialogClose>
          <Button
            className="h-13 rounded-[10px] px-5.5 text-lg font-bold md:h-11.5 md:min-w-30 md:text-[17px]"
            disabled={picked.length === 0}
            onClick={() => onDone(picked)}
          >
            {t("orders.picker.ok")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ProductRow({
  summary,
  active,
  count,
  onOpen,
}: {
  summary: ProductSummary;
  active: boolean;
  count: number;
  onOpen: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <li className="max-md:border-b">
      <button
        type="button"
        aria-current={active ? "true" : undefined}
        className={cn(
          "flex min-h-16 w-full cursor-pointer items-center gap-2.5 rounded-[10px] px-2 py-1.75 text-left hover:bg-muted max-md:min-h-17",
          active && "bg-accent hover:bg-accent",
        )}
        onClick={onOpen}
      >
        <Thumbnail src={summary.thumbnail} className="size-12 max-md:size-13" />
        <span className="min-w-0 flex-1">
          <span className="block truncate font-semibold">{summary.name}</span>
          <span className="block text-sm text-muted-foreground">
            {summary.activeVariants > 1 && (
              <>{t("products.variantCount", { count: format.number(summary.activeVariants) })} · </>
            )}
            <Available count={summary.available} />
          </span>
        </span>
        {count > 0 && (
          <span className="flex h-6.5 min-w-6.5 flex-none items-center justify-center rounded-full bg-primary px-1.75 text-sm font-bold text-primary-foreground">
            {format.number(count)}
          </span>
        )}
        <ChevronRight aria-hidden="true" className="size-4.5 flex-none text-muted-foreground" />
      </button>
    </li>
  );
}
