import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { currentLanguage, setLanguage } from "@/i18n";
import { cn } from "@/lib/utils";

/** Shows the other language's name, in that language; one tap switches. */
export function LanguageToggle({ className }: { className?: string }) {
  const { t } = useTranslation();
  const other = currentLanguage() === "bn" ? "en" : "bn";
  return (
    <Button
      variant="ghost"
      className={cn("text-primary hover:text-accent-foreground", className)}
      aria-label={t("language.switch")}
      onClick={() => void setLanguage(other)}
    >
      <span lang={other}>{t("language.other")}</span>
    </Button>
  );
}
