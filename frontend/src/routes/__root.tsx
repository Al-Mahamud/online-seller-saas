import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext, Link, Outlet } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
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
      <Outlet />
      <Toaster position="top-center" />
    </>
  );
}

function NotFound() {
  const { t } = useTranslation();
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center">
      <h1 className="text-xl font-semibold">{t("page.notFound")}</h1>
      <Link to="/" className="flex min-h-11 items-center font-semibold text-primary">
        {t("page.backToToday")}
      </Link>
    </main>
  );
}
