import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, settingsQuery, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { Section } from "@/components/layout/Section";
import { SEARCH_THEMES, type SearchTheme } from "@/components/search/theme";
import { cn } from "@/lib/utils";

/** What each look looks like, small: its ground, the bar and the button. */
const PREVIEW: Record<SearchTheme, { ground: string; button: string; line: string }> = {
  LEDGER: { ground: "bg-ledger", button: "bg-marigold-text", line: "bg-marigold" },
  MINT: { ground: "bg-accent border border-mint-line", button: "bg-ledger", line: "bg-primary" },
};

/** The owner picks the search panel's colours for everyone; others see the choice. Saved on picking. */
export function SearchColourSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const isOwner = hasRole(useBusiness(), "OWNER");
  const { data: settings } = useQuery(settingsQuery);
  // Shown picked at once; put back if saving fails.
  const [picked, setPicked] = useState<SearchTheme>();
  const save = useMutation({
    mutationFn: (searchTheme: SearchTheme) => call(api.PUT("/api/v1/business/search-theme", { body: { searchTheme } })),
    onSuccess: (updated) => {
      queryClient.setQueryData(settingsQuery.queryKey, updated);
      toast.success(t("search.theme.saved"));
    },
    onSettled: () => setPicked(undefined),
  });
  const current = picked ?? settings?.searchTheme ?? "LEDGER";
  return (
    <Section title={t("search.theme.title")}>
      <fieldset className="flex flex-col gap-3" disabled={!isOwner || !settings}>
        <legend className="mb-1 text-[15px] text-muted-foreground">
          {t("search.theme.hint")} {!isOwner && t("search.theme.ownerOnly")}
        </legend>
        <div className="grid gap-3 sm:grid-cols-2">
          {SEARCH_THEMES.map((theme) => {
            const look = PREVIEW[theme];
            const on = current === theme;
            return (
              <label
                key={theme}
                className={cn(
                  "flex cursor-pointer flex-col gap-3 rounded-2xl border-[1.5px] p-3 has-focus-visible:ring-3 has-focus-visible:ring-ring/50 has-disabled:cursor-default",
                  on ? "border-primary bg-accent/40" : "border-input bg-card hover:bg-muted",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn("relative flex flex-col gap-2 rounded-xl p-3 pl-6", look.ground)}
                >
                  <span className={cn("absolute inset-y-0 left-2.5 w-0.5", look.line)} />
                  <span className="flex h-9 items-center gap-2 rounded-lg bg-card pr-1 pl-2.5 shadow-sm">
                    <Search className="size-4 text-primary" />
                    <span className="h-2 flex-1 rounded bg-muted" />
                    <span className={cn("h-7 w-12 rounded-md", look.button)} />
                  </span>
                  <span className="flex gap-1.5">
                    <span className="h-4 w-14 rounded-full bg-white/40" />
                    <span className="h-4 w-10 rounded-full bg-white/40" />
                  </span>
                </span>
                <span className="flex items-start gap-2.5">
                  <input
                    type="radio"
                    name="search-theme"
                    value={theme}
                    checked={on}
                    className="mt-1 size-5 shrink-0 accent-primary"
                    onChange={() => {
                      setPicked(theme);
                      save.mutate(theme);
                    }}
                  />
                  <span>
                    <span className="block font-semibold">{t(`search.theme.${theme}`)}</span>
                    <span className="block text-sm text-muted-foreground">{t(`search.theme.${theme}Hint`)}</span>
                  </span>
                </span>
              </label>
            );
          })}
        </div>
      </fieldset>
    </Section>
  );
}
