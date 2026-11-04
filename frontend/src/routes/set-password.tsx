import { createFileRoute, redirect } from "@tanstack/react-router";
import { meQuery } from "@/api/session";
import { FirstPasswordPage } from "@/features/staff/FirstPasswordPage";

/** The first sign-in with a starting password a business gave: their own password first. */
export const Route = createFileRoute("/set-password")({
  beforeLoad: async ({ context }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (!me) {
      throw redirect({ to: "/sign-in" });
    }
    if (!me.mustChangePassword) {
      throw redirect({ to: "/" });
    }
  },
  component: FirstPasswordPage,
});
