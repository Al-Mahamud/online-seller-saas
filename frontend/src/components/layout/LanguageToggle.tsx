import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { type Me, meQuery } from "@/api/session";
import { Button } from "@/components/ui/button";
import { currentLanguage, type Language, setLanguage } from "@/i18n";
import { cn } from "@/lib/utils";

/** Signed in, a language chosen is saved on the account: the server answers in the account's language. */
export function useChangeLanguage() {
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: (locale: Language) => call(api.PATCH("/api/v1/me", { body: { locale } })),
    onSuccess: (me) => {
      queryClient.setQueryData(meQuery.queryKey, me);
      // What the server wrote (notices, report labels) was in the old language until now.
      void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== meQuery.queryKey[0] });
    },
  });
  return async (language: Language) => {
    await setLanguage(language);
    const me = queryClient.getQueryData(meQuery.queryKey);
    if (me) {
      const updated: Me = { ...me, locale: language };
      queryClient.setQueryData(meQuery.queryKey, updated);
      save.mutate(language);
    }
  };
}

/**
 * Shows the other language's name, in that language; one tap switches. That visible name is also
 * the button's accessible name (WCAG 2.5.3, label in name), so voice control can say what it sees.
 */
export function LanguageToggle({ className }: { className?: string }) {
  const { t } = useTranslation();
  const changeLanguage = useChangeLanguage();
  const other = currentLanguage() === "bn" ? "en" : "bn";
  return (
    <Button
      variant="ghost"
      className={cn("text-primary hover:text-accent-foreground", className)}
      onClick={() => void changeLanguage(other)}
    >
      <span lang={other}>{t("language.other")}</span>
    </Button>
  );
}
