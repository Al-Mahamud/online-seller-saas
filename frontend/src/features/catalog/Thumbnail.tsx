import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

/** A product's small picture, or a tile saying it has none. */
export function Thumbnail({ src, className }: { src: string | null; className: string }) {
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
