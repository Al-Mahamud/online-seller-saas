import { Link, type LinkProps } from "@tanstack/react-router";
import { Plus } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Brand } from "./Brand";
import { LanguageToggle } from "./LanguageToggle";

type Tab = { to: LinkProps["to"]; label: "nav.today" | "nav.orders" | "nav.products" | "nav.more"; exact?: boolean };

const TABS: Tab[] = [
  { to: "/", label: "nav.today", exact: true },
  { to: "/orders", label: "nav.orders" },
  { to: "/products", label: "nav.products" },
  { to: "/more", label: "nav.more" },
];

/** The business switcher takes this place once there are businesses (F2). */
function HomeLink() {
  return (
    <Link to="/" className="flex min-h-11 items-center rounded-lg no-underline">
      <Brand />
    </Link>
  );
}

/**
 * The frame around every signed-in screen, as in the mockup. On phones: a header with the business
 * and the language, four tabs at the bottom and a "+" for a new order. From 768 px: the same tabs as
 * a sidebar.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="min-h-dvh md:flex">
      <nav
        aria-label={t("nav.main")}
        className="sticky top-0 hidden h-dvh w-58 shrink-0 flex-col gap-1 border-r bg-card px-3.5 py-5 md:flex"
      >
        <div className="mb-3.5 px-1">
          <HomeLink />
        </div>
        {TABS.map((tab) => (
          <Link
            key={tab.label}
            to={tab.to}
            activeOptions={{ exact: tab.exact }}
            className="flex min-h-11 items-center rounded-lg px-3 text-[#2B342F] no-underline hover:bg-muted data-[status=active]:bg-accent data-[status=active]:font-semibold data-[status=active]:text-accent-foreground"
          >
            {t(tab.label)}
          </Link>
        ))}
        <LanguageToggle className="mt-auto self-start" />
      </nav>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-2.5 border-b bg-card px-4 py-1.5 md:hidden">
          <HomeLink />
          <LanguageToggle />
        </header>

        <main className="flex-1 px-4 pt-4 pb-28 md:px-10 md:pt-8 md:pb-12">{children}</main>
      </div>

      <Link
        to="/orders/new"
        aria-label={t("orders.new")}
        className="fixed right-4.5 bottom-[calc(84px+env(safe-area-inset-bottom))] z-20 flex size-14 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-[0_6px_16px_rgba(23,32,27,0.18)] md:hidden"
      >
        <Plus aria-hidden="true" className="size-6" strokeWidth={2.4} />
      </Link>

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
