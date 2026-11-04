import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { businessQuery } from "@/api/business";
import { accountLanguage, meQuery } from "@/api/session";
import { AppShell } from "@/components/layout/AppShell";
import { LowStockWindow, lowStockSearch } from "@/features/catalog/LowStockWindow";
import { currentLanguage, setLanguage } from "@/i18n";

const SIGNED_IN = "mojud.signedIn";

function wasSignedIn(): boolean {
  try {
    return localStorage.getItem(SIGNED_IN) === "1";
  } catch {
    return false;
  }
}

function rememberSignedIn(signedIn: boolean) {
  try {
    if (signedIn) {
      localStorage.setItem(SIGNED_IN, "1");
    } else {
      localStorage.removeItem(SIGNED_IN);
    }
  } catch {
    // Only a head start: without it, things happen one after the other.
  }
}

/** Everything behind signing in. Signed out, to sign-in, and back here afterwards. */
export const Route = createFileRoute("/_app")({
  validateSearch: lowStockSearch,
  beforeLoad: async ({ context, location }) => {
    // Someone who was signed in last time very likely still is: asking for their business alongside
    // saves a round of waiting. If not, this is just another request told the session has ended.
    if (wasSignedIn()) {
      void context.queryClient.prefetchQuery(businessQuery);
    }
    const me = await context.queryClient.ensureQueryData(meQuery);
    rememberSignedIn(!!me);
    if (!me) {
      throw redirect({ to: "/sign-in", search: location.href === "/" ? {} : { redirect: location.href } });
    }
    if (me.mustChangePassword) {
      throw redirect({ to: "/set-password" });
    }
    if (accountLanguage(me) !== currentLanguage()) {
      await setLanguage(accountLanguage(me));
    }
    const business = await context.queryClient.ensureQueryData(businessQuery);
    if (!business) {
      throw redirect({ to: "/welcome" });
    }
    return { me, business };
  },
  component: () => (
    <AppShell>
      <Outlet />
      <LowStockWindow />
    </AppShell>
  ),
});
