import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

/** The mark and the app's name; `onDark` for the sign-in pages' dark panel. */
export function Brand({ large = false, onDark = false }: { large?: boolean; onDark?: boolean }) {
  const { t } = useTranslation();
  return (
    <span className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className={cn(
          "flex items-center justify-center rounded-lg font-bold",
          onDark ? "bg-white text-primary" : "bg-primary text-primary-foreground",
          large ? "size-9.5 text-xl" : "size-8 text-sm",
        )}
      >
        ম
      </span>
      <span
        className={cn(
          onDark ? "text-white" : "text-foreground",
          large ? "text-[22px] font-bold" : "text-base font-semibold",
        )}
      >
        {t("app.name")}
      </span>
    </span>
  );
}
