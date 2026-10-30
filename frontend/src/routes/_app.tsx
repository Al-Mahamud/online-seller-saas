import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
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
    return { me };
  },
  component: () => (
    <AppShell>
      <Outlet />
    </AppShell>
  ),
});
