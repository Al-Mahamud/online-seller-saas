import { Link, type LinkProps } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { can, initials, useBusiness } from "@/api/business";
import { useMe } from "@/api/session";
import { Button } from "@/components/ui/button";
import { useSignOut } from "./useSignOut";

const TAB =
  "flex min-h-11 flex-none items-center rounded-full border-[1.5px] bg-card px-4 text-[15px] text-foreground no-underline hover:text-foreground data-[status=active]:border-primary data-[status=active]:bg-primary data-[status=active]:font-bold data-[status=active]:text-primary-foreground md:-mb-px md:min-h-11.5 md:rounded-none md:border-0 md:border-b-[3px] md:border-transparent md:bg-transparent md:px-3.5 md:text-base md:data-[status=active]:border-primary md:data-[status=active]:bg-transparent md:data-[status=active]:text-accent-foreground";

/** Around the account's pages: who's signed in, the way between the pages, and signing out. */
export function ProfileFrame({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const me = useMe();
  const business = useBusiness();
  const signOut = useSignOut();
  const tabs: { to: LinkProps["to"]; label: string }[] = [
    { to: "/settings/account", label: t("account.info") },
    { to: "/settings/security", label: t("account.security") },
    ...(can(business, "SETTINGS")
      ? [{ to: "/settings/notifications" as const, label: t("notifications.emailChoices") }]
      : []),
  ];
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the person share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-3 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("account.title")}</h1>
        <section
          aria-label={t("account.identity")}
          className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-4.5 md:pr-6 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          <div className="flex min-w-0 items-center gap-3 md:gap-3.5">
            <span
              aria-hidden="true"
              className="flex size-13.5 flex-none items-center justify-center rounded-full bg-marigold-text text-[22px] font-bold text-ledger md:size-15 md:text-2xl"
            >
              {initials(me.name)}
            </span>
            <span className="min-w-0">
              <span className="block text-xl font-bold wrap-anywhere md:text-[22px]">{me.name}</span>
              <span className="block text-sm text-ledger-text md:text-[15px]">
                {t("auth.userId.label")}:{" "}
                <span lang="en" className="wrap-anywhere">
                  {me.userId}
                </span>
              </span>
            </span>
          </div>
          <button
            type="button"
            className="min-h-11.5 cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text px-4.5 text-[15px] font-semibold text-white hover:bg-white/10 focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text disabled:opacity-60 max-md:hidden"
            disabled={signOut.isPending}
            onClick={() => signOut.mutate()}
          >
            {t("account.signOut")}
          </button>
        </section>
      </div>

      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5 md:gap-4">
        <nav aria-label={t("account.parts")} className="flex gap-2 max-md:overflow-x-auto md:gap-1 md:border-b">
          {tabs.map((tab) => (
            <Link key={tab.label} to={tab.to} className={TAB}>
              {tab.label}
            </Link>
          ))}
        </nav>
        {children}
        <Button
          variant="outline"
          className="h-12 rounded-[10px] border-[1.5px] text-base font-semibold md:hidden"
          disabled={signOut.isPending}
          onClick={() => signOut.mutate()}
        >
          {t("account.signOut")}
        </Button>
      </div>
    </div>
  );
}
