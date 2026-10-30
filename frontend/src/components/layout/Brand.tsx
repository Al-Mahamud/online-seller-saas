import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

/** The mark and the app's name. */
export function Brand({ large = false }: { large?: boolean }) {
  const { t } = useTranslation();
  return (
    <span className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className={cn(
          "flex items-center justify-center rounded-lg bg-primary font-bold text-primary-foreground",
          large ? "size-9.5 text-xl" : "size-8 text-sm",
        )}
      >
        ম
      </span>
      <span className={cn("text-foreground", large ? "text-[22px] font-bold" : "text-base font-semibold")}>
        {t("app.name")}
      </span>
    </span>
  );
}
