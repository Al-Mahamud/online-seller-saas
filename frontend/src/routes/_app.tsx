import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { businessQuery } from "@/api/business";
import { accountLanguage, meQuery } from "@/api/session";
import { AppShell } from "@/components/layout/AppShell";
import { currentLanguage, setLanguage } from "@/i18n";

/** Everything behind signing in. Signed out, to sign-in, and back here afterwards. */
export const Route = createFileRoute("/_app")({
  beforeLoad: async ({ context, location }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (!me) {
      throw redirect({ to: "/sign-in", search: location.href === "/" ? {} : { redirect: location.href } });
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
    </AppShell>
  ),
});
