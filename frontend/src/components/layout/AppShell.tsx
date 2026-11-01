import { Link, type LinkProps, useLocation } from "@tanstack/react-router";
import { Plus } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { SubscriptionBanner } from "@/features/billing/SubscriptionBanner";
import { useCanWrite } from "@/features/billing/status";
import { BusinessSwitcher } from "@/features/business/BusinessSwitcher";
import { DeletionBanner } from "@/features/business/DeletionBanner";
import { NotificationBell, NotificationLink } from "@/features/notifications/NotificationBell";
import { LanguageToggle } from "./LanguageToggle";
import { MANAGE_LINKS, type NavLabel, type NavLink, TAB_LINKS, WORK_LINKS } from "./navLinks";
import { PlanLimitHost } from "./PlanLimitHost";

const TABS: { to: LinkProps["to"]; label: NavLabel | "nav.more"; exact?: boolean }[] = [
  ...TAB_LINKS,
  { to: "/more", label: "nav.more" },
];

const SIDE_LINK =
  "flex min-h-10.5 flex-none items-center gap-2.75 rounded-[10px] px-2.5 text-[15.5px] text-white/90 no-underline hover:bg-white/8 hover:text-white focus-visible:outline-3 focus-visible:-outline-offset-3 focus-visible:outline-marigold-text data-[status=active]:bg-white/14 data-[status=active]:font-bold data-[status=active]:text-white [&_svg]:size-4.75 [&_svg]:flex-none [&_svg]:text-ledger-text data-[status=active]:[&_svg]:text-marigold-text";

const FLOATING_BUTTON =
  "fixed right-4.5 bottom-[calc(84px+env(safe-area-inset-bottom))] z-20 flex size-14 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-[0_6px_16px_rgba(23,32,27,0.18)] md:hidden";

function SideLink({ link }: { link: NavLink }) {
  const { t } = useTranslation();
  return (
    <Link to={link.to} activeOptions={{ exact: link.exact }} className={SIDE_LINK}>
      <link.icon aria-hidden="true" />
      {t(link.label)}
    </Link>
  );
}

/**
 * The frame around every signed-in screen. On phones: a header with the business and the language,
 * four tabs at the bottom and a "+" for a new order. From 768 px: a sidebar with every page.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  // It would only cover the buttons of the account pages, and an order's own.
  const { pathname } = useLocation();
  const business = useBusiness();
  const canWrite = useCanWrite();
  const onProducts = /^\/products\/?$/.test(pathname);
  const showsNewOrder =
    canWrite && !/^\/(more|settings|billing|orders\/|cod|reports|notifications|products)/.test(pathname);
  const showsNewProduct = canWrite && onProducts && hasRole(business, "MANAGER");
  const manage = MANAGE_LINKS.filter((link) => !link.role || hasRole(business, link.role));
  return (
    <div className="min-h-dvh md:flex">
      <nav
        aria-label={t("nav.main")}
        className="sticky top-0 hidden h-dvh w-62 shrink-0 flex-col gap-3.5 bg-ledger px-3 py-4 text-white md:flex"
      >
        <BusinessSwitcher wide />
        <div className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto">
          {[...TAB_LINKS, ...WORK_LINKS].map((link) => (
            <SideLink key={link.label} link={link} />
          ))}
          {manage.length > 0 && (
            <>
              <p className="mt-3.5 mb-1 px-2.5 text-[13px] font-semibold text-ledger-text">{t("nav.manage")}</p>
              {manage.map((link) => (
                <SideLink key={link.label} link={link} />
              ))}
            </>
          )}
        </div>
        <div className="flex flex-none flex-col gap-0.5 border-t border-ledger-outline pt-2.5">
          <NotificationLink className={SIDE_LINK} />
          <LanguageToggle className="self-start text-marigold-text hover:bg-white/8 hover:text-marigold-text" />
        </div>
      </nav>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-2.5 border-b bg-card px-4 py-1.5 md:hidden">
          <BusinessSwitcher />
          <div className="flex flex-none items-center gap-1">
            <NotificationBell />
            <LanguageToggle />
          </div>
        </header>

        <SubscriptionBanner />
        <DeletionBanner />
        <main className="flex-1 px-4 pt-4 pb-28 md:px-10 md:pt-8 md:pb-12">{children}</main>
      </div>

      {showsNewOrder && (
        <Link to="/orders/new" aria-label={t("orders.new")} className={FLOATING_BUTTON}>
          <Plus aria-hidden="true" className="size-6" strokeWidth={2.4} />
        </Link>
      )}
      {showsNewProduct && (
        <Link to="/products/new" aria-label={t("products.new")} className={FLOATING_BUTTON}>
          <Plus aria-hidden="true" className="size-6" strokeWidth={2.4} />
        </Link>
      )}
      <PlanLimitHost />

      <nav
        aria-label={t("nav.main")}
        className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-4 border-t bg-card pb-[env(safe-area-inset-bottom)] md:hidden"
      >
        {TABS.map((tab) => (
          <Link
            key={tab.label}
            to={tab.to}
            activeOptions={{ exact: tab.exact }}
            className="flex h-17 items-center justify-center text-sm text-muted-foreground no-underline data-[status=active]:font-bold data-[status=active]:text-accent-foreground"
          >
            {t(tab.label)}
          </Link>
        ))}
      </nav>
    </div>
  );
}
