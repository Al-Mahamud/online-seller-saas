import { getRouteApi, Link, type LinkProps, useNavigate } from "@tanstack/react-router";
import { lazy, type ReactNode, Suspense } from "react";
import { can, useBusiness } from "@/api/business";

const route = getRouteApi("/_app");
const LowStockDialog = lazy(() => import("./LowStockDialog"));

/** `?lowStock=true` on any page: the running-low window over it, so Back closes it. */
export function lowStockSearch(search: Record<string, unknown>): { lowStock?: true } {
  const open =
    search.lowStock === true || search.lowStock === 1 || search.lowStock === "1" || search.lowStock === "true";
  return open ? { lowStock: true } : {};
}

/** The running-low window, open while the address asks for it. */
export function LowStockWindow() {
  const { lowStock } = route.useSearch();
  const navigate = useNavigate();
  const business = useBusiness();
  if (!lowStock || !can(business, "STOCK")) {
    return null;
  }
  return (
    <Suspense fallback={null}>
      <LowStockDialog
        onClose={() => void navigate({ to: ".", search: (prev) => ({ ...prev, lowStock: undefined }), replace: true })}
      />
    </Suspense>
  );
}

/** A link that opens the running-low window over the page it's on. */
export function LowStockLink({ className, children }: { className?: string; children: ReactNode }) {
  const link = { to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, lowStock: true }) } as LinkProps;
  return (
    <Link {...link} className={className}>
      {children}
    </Link>
  );
}
