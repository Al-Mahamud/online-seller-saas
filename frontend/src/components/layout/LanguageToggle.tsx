import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { type Me, meQuery } from "@/api/session";
import { Button } from "@/components/ui/button";
import { currentLanguage, type Language, setLanguage } from "@/i18n";
import { cn } from "@/lib/utils";

/**
 * Shows the other language's name, in that language; one tap switches. That visible name is also
 * the button's accessible name (WCAG 2.5.3, label in name), so voice control can say what it sees.
 * Signed in, the choice is saved on the account: the server answers in the account's language.
 */
export function LanguageToggle({ className }: { className?: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: (locale: Language) => call(api.PATCH("/api/v1/me", { body: { locale } })),
    onSuccess: (me) => queryClient.setQueryData(meQuery.queryKey, me),
  });
  const other = currentLanguage() === "bn" ? "en" : "bn";

  async function switchLanguage() {
    await setLanguage(other);
    const me = queryClient.getQueryData(meQuery.queryKey);
    if (me) {
      const updated: Me = { ...me, locale: other };
      queryClient.setQueryData(meQuery.queryKey, updated);
      save.mutate(other);
    }
  }

  return (
    <Button
      variant="ghost"
      className={cn("text-primary hover:text-accent-foreground", className)}
      onClick={() => void switchLanguage()}
    >
      <span lang={other}>{t("language.other")}</span>
    </Button>
  );
}
