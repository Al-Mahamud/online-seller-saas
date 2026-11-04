import { Lock, Search } from "lucide-react";
import { useTranslation } from "react-i18next";
import { SEARCH_THEMES, type SearchTheme } from "@/components/search/theme";
import { cn } from "@/lib/utils";

/** What each look looks like, small: its ground, the bar and the button. */
const PREVIEW: Record<SearchTheme, { ground: string; button: string; line: string }> = {
  LEDGER: { ground: "bg-ledger", button: "bg-marigold-text", line: "bg-marigold" },
  MINT: { ground: "bg-accent border border-mint-line", button: "bg-ledger", line: "bg-primary" },
};

/**
 * The search panel's colours, for everyone in the business. The owner picks, and it is saved with
 * the rest of the page; others see the choice.
 */
export function SearchColourChoice({
  value,
  onChange,
  canChange,
}: {
  value: SearchTheme;
  onChange: (theme: SearchTheme) => void;
  canChange: boolean;
}) {
  const { t } = useTranslation();
  return (
    <fieldset className="flex flex-col gap-3" disabled={!canChange}>
      <legend className="sr-only">{t("search.theme.title")}</legend>
      <div className="grid gap-3 sm:grid-cols-2">
        {SEARCH_THEMES.map((theme) => {
          const look = PREVIEW[theme];
          const on = value === theme;
          return (
            <label
              key={theme}
              className={cn(
                "flex cursor-pointer flex-col gap-2.5 rounded-[14px] border-[1.5px] p-3 has-focus-visible:ring-3 has-focus-visible:ring-ring/50 has-disabled:cursor-default",
                on ? "border-primary bg-[#F3F9F6]" : "border-input bg-card hover:bg-muted",
                !canChange && !on && "hidden",
              )}
            >
              <span
                aria-hidden="true"
                className={cn("relative flex flex-col gap-2 rounded-[10px] p-3 pl-6", look.ground)}
              >
                <span className={cn("absolute inset-y-0 left-2.5 w-0.5", look.line)} />
                <span className="flex h-8.5 items-center gap-2 rounded-lg bg-card pr-1 pl-2.5 shadow-sm">
                  <Search className="size-4 text-primary" />
                  <span className="h-2 flex-1 rounded bg-muted" />
                  <span className={cn("h-6.5 w-11.5 rounded-md", look.button)} />
                </span>
                <span className="flex gap-1.5">
                  <span className="h-3.5 w-13.5 rounded-full bg-white/40" />
                  <span className="h-3.5 w-10 rounded-full bg-white/40" />
                </span>
              </span>
              <span className="flex items-start gap-2.5">
                <input
                  type="radio"
                  name="search-theme"
                  value={theme}
                  checked={on}
                  className="mt-1 size-5 shrink-0 accent-primary"
                  onChange={() => onChange(theme)}
                />
                <span>
                  <span className="block font-bold">{t(`search.theme.${theme}`)}</span>
                  <span className="block text-sm text-[#3E4842]">{t(`search.theme.${theme}Hint`)}</span>
                </span>
              </span>
            </label>
          );
        })}
      </div>
      {!canChange && (
        <p className="flex items-start gap-1.5 text-sm text-[#3E4842]">
          <Lock aria-hidden="true" className="mt-0.5 size-4 flex-none text-muted-foreground" />
          {t("search.theme.ownerOnly")}
        </p>
      )}
    </fieldset>
  );
}
