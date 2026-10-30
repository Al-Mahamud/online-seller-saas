import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { signedOut } from "@/api/session";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/_app/more")({
  component: More,
});

/** The account now; customers, staff, settings, billing and the rest from F2 onwards. */
function More() {
  const { t } = useTranslation();
  const { me } = Route.useRouteContext();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const signOut = useMutation({
    mutationFn: () => call(api.POST("/api/v1/auth/logout")),
    onSuccess: () => {
      signedOut(queryClient);
      void navigate({ to: "/sign-in" });
    },
  });

  return (
    <>
      <PageHeader title={t("nav.more")} />
      <section className="flex max-w-md flex-col gap-3 rounded-xl border bg-card p-4">
        <div>
          <p className="font-semibold">{me.name}</p>
          <p className="text-sm text-muted-foreground">{me.email ?? me.phone}</p>
        </div>
        <Button variant="outline" disabled={signOut.isPending} onClick={() => signOut.mutate()}>
          {t("account.signOut")}
        </Button>
      </section>
    </>
  );
}
