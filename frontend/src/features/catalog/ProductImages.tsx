import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { uploadImage } from "@/api/uploads";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { type Product, productChanged } from "./api";

const MAX_IMAGES = 8;

type Image = Product["images"][number];

/** The first image is the main one, shown in lists. */
export function ProductImages({ product, canEdit }: { product: Product; canEdit: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [openId, setOpenId] = useState<string>();
  const add = useMutation({
    mutationFn: async (files: File[]) => {
      let latest: Product | undefined;
      // One at a time, in the order chosen.
      for (const file of files) {
        const uploaded = await uploadImage(file);
        latest = await call(
          api.POST("/api/v1/products/{id}/images", {
            params: { path: { id: product.id } },
            body: { fileId: uploaded.id },
          }),
        );
      }
      return latest;
    },
    onSuccess: (updated) => {
      if (updated) {
        productChanged(queryClient, updated);
      }
    },
    // Some may have gone in before one was refused.
    onError: () => void queryClient.invalidateQueries({ queryKey: ["product", product.id] }),
  });
  const open = product.images.find((image) => image.id === openId);
  if (!canEdit && product.images.length === 0) {
    return null;
  }
  return (
    <Section title={t("products.images.title")}>
      <p className="text-sm text-muted-foreground">
        {t("products.images.count", { count: format.number(product.images.length), max: format.number(MAX_IMAGES) })}
      </p>
      <div className="grid grid-cols-4 gap-2.5 md:grid-cols-8">
        {product.images.map((image, index) => {
          const alt = t("products.images.alt", { number: format.number(index + 1), name: product.name });
          return canEdit ? (
            <button
              key={image.id}
              type="button"
              className="relative cursor-pointer overflow-hidden rounded-lg border"
              onClick={() => setOpenId(image.id)}
            >
              <span className="sr-only">{t("products.images.open", { number: format.number(index + 1) })}</span>
              <ImageTile image={image} alt="" isMain={index === 0} />
            </button>
          ) : (
            <div key={image.id} className="relative overflow-hidden rounded-lg border">
              <ImageTile image={image} alt={alt} isMain={index === 0} />
            </div>
          );
        })}
        {canEdit && product.images.length < MAX_IMAGES && (
          <>
            <input
              ref={input}
              type="file"
              multiple
              accept="image/jpeg,image/png,image/webp"
              aria-label={t("products.images.add")}
              className="sr-only"
              tabIndex={-1}
              onChange={(event) => {
                const files = [...(event.target.files ?? [])].slice(0, MAX_IMAGES - product.images.length);
                if (files.length > 0) {
                  add.mutate(files);
                }
                event.target.value = "";
              }}
            />
            <button
              type="button"
              disabled={add.isPending}
              className="flex aspect-square cursor-pointer flex-col items-center justify-center gap-1 rounded-lg border border-dashed border-input bg-card text-xs"
              onClick={() => input.current?.click()}
            >
              <Plus aria-hidden="true" className="size-5" />
              {t("products.images.add")}
            </button>
          </>
        )}
      </div>
      {canEdit && <p className="text-sm text-muted-foreground">{t("products.images.hint")}</p>}
      {open && <ImageDialog product={product} image={open} onClose={() => setOpenId(undefined)} />}
    </Section>
  );
}

function ImageTile({ image, alt, isMain }: { image: Image; alt: string; isMain: boolean }) {
  const { t } = useTranslation();
  return (
    <>
      <img src={image.small} alt={alt} loading="lazy" className="aspect-square w-full object-cover" />
      {isMain && (
        <span className="absolute inset-x-0 bottom-0 bg-foreground/75 text-center text-xs text-background">
          {t("products.images.main")}
        </span>
      )}
    </>
  );
}

function ImageDialog({ product, image, onClose }: { product: Product; image: Image; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const index = product.images.findIndex((other) => other.id === image.id);
  const path = { id: product.id, imageId: image.id };
  const applied = (updated: Product) => productChanged(queryClient, updated);
  const reorder = useMutation({
    mutationFn: (ids: string[]) =>
      call(api.PUT("/api/v1/products/{id}/images/order", { params: { path: { id: product.id } }, body: { ids } })),
    onSuccess: applied,
  });
  const showFor = useMutation({
    mutationFn: (variantId: string | undefined) =>
      call(api.PATCH("/api/v1/products/{id}/images/{imageId}", { params: { path }, body: { variantId } })),
    onSuccess: applied,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/products/{id}/images/{imageId}", { params: { path } })),
    onSuccess: (updated) => {
      applied(updated);
      onClose();
    },
  });
  const ids = product.images.map((other) => other.id);
  const moved = (to: number) => {
    const next = ids.filter((id) => id !== image.id);
    next.splice(to, 0, image.id);
    return next;
  };
  const busy = reorder.isPending || showFor.isPending || remove.isPending;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("products.images.dialogTitle")}</DialogTitle>
        </DialogHeader>
        <img src={image.small} alt="" className="mx-auto size-40 rounded-lg object-cover" />
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="image-shown-for" className="text-[15px] leading-normal font-semibold">
            {t("products.images.showFor")}
          </Label>
          <select
            id="image-shown-for"
            className="h-12 w-full rounded-lg border border-input bg-card px-2 md:h-11"
            value={image.variantId ?? ""}
            disabled={busy}
            onChange={(event) => showFor.mutate(event.target.value || undefined)}
          >
            <option value="">{t("products.images.allVariants")}</option>
            {product.variants
              .filter((variant) => variant.label)
              .map((variant) => (
                <option key={variant.id} value={variant.id}>
                  {variant.label}
                </option>
              ))}
          </select>
        </div>
        <div className="flex flex-col gap-2">
          {index > 0 && (
            <Button variant="outline" disabled={busy} onClick={() => reorder.mutate(moved(0))}>
              {t("products.images.makeMain")}
            </Button>
          )}
          <div className="grid grid-cols-2 gap-2">
            <Button variant="outline" disabled={busy || index === 0} onClick={() => reorder.mutate(moved(index - 1))}>
              {t("products.images.earlier")}
            </Button>
            <Button
              variant="outline"
              disabled={busy || index === ids.length - 1}
              onClick={() => reorder.mutate(moved(index + 1))}
            >
              {t("products.images.later")}
            </Button>
          </div>
          <Button variant="destructive" disabled={busy} onClick={() => remove.mutate()}>
            {t("products.images.remove")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
