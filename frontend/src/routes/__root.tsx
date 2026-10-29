import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Link, Outlet } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { AppShell } from "@/components/layout/AppShell";
import { Toaster } from "@/components/ui/sonner";

export interface RouterContext {
  queryClient: QueryClient;
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: Root,
  notFoundComponent: NotFound,
});

function Root() {
  return (
    <>
      <AppShell>
        <Outlet />
      </AppShell>
      <Toaster position="top-center" />
    </>
  );
}

function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-start gap-3">
      <p>{t("page.notFound")}</p>
      <Link to="/" className="font-semibold text-primary">
        {t("page.backToToday")}
      </Link>
    </div>
  );
}
