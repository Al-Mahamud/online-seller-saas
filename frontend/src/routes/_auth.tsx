import { createFileRoute, redirect } from "@tanstack/react-router";
import { meQuery } from "@/api/session";
import { AuthLayout } from "@/features/auth/AuthLayout";

/** Signing in and up, for people who aren't signed in. */
export const Route = createFileRoute("/_auth")({
  beforeLoad: async ({ context }) => {
    if (await context.queryClient.ensureQueryData(meQuery)) {
      throw redirect({ to: "/" });
    }
  },
  component: AuthLayout,
});
