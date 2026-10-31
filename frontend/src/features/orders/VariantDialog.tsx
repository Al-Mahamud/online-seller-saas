import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { Product, Variant } from "@/features/catalog/api";
import { useFormat } from "@/lib/format";
import { Available } from "./ItemPicker";

/** Which of the product's variants, with what's available of each. */
export default function VariantDialog({
  product,
  onPick,
  onClose,
}: {
  product: Product;
  onPick: (variant: Variant) => void;
  onClose: () => void;
}) {
  const format = useFormat();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-dvh flex-col">
        <DialogHeader>
          <DialogTitle>{product.name}</DialogTitle>
        </DialogHeader>
        <ul className="min-h-0 flex-1 overflow-y-auto">
          {product.variants
            .filter((variant) => variant.status === "ACTIVE")
            .map((variant) => (
              <li key={variant.id} className="border-b last:border-b-0">
                <button
                  type="button"
                  className="flex min-h-11 w-full cursor-pointer items-center justify-between gap-3 py-2 text-left"
                  onClick={() => onPick(variant)}
                >
                  <span className="min-w-0">
                    <span className="block font-semibold">{variant.label || product.name}</span>
                    <Available count={variant.available} />
                  </span>
                  <span className="flex-none font-semibold">{format.taka(variant.price)}</span>
                </button>
              </li>
            ))}
        </ul>
      </DialogContent>
    </Dialog>
  );
}
