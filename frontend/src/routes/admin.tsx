import { createFileRoute, redirect } from "@tanstack/react-router";
import { accountLanguage, meQuery } from "@/api/session";
import { AdminLayout } from "@/features/admin/AdminLayout";
import { currentLanguage, setLanguage } from "@/i18n";

/** Platform admins only. Apart from the shop screens: an admin may run no shop at all. */
export const Route = createFileRoute("/admin")({
  beforeLoad: async ({ context, location }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (!me) {
      throw redirect({ to: "/sign-in", search: { redirect: location.href } });
    }
    if (!me.platformAdmin) {
      throw redirect({ to: "/" });
    }
    if (accountLanguage(me) !== currentLanguage()) {
      await setLanguage(accountLanguage(me));
    }
  },
  component: AdminLayout,
});
