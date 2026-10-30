import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { signedOut, useMe } from "@/api/session";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/_app/more")({
  component: More,
});

/** The account now; customers, staff, settings, billing and the rest from F2 onwards. */
function More() {
  const { t } = useTranslation();
  const me = useMe();
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
        <nav aria-label={t("account.title")} className="flex flex-col">
          <MoreLink to="/settings/account" label={t("account.title")} />
          <MoreLink to="/settings/security" label={t("account.security")} />
        </nav>
        <Button variant="outline" disabled={signOut.isPending} onClick={() => signOut.mutate()}>
          {t("account.signOut")}
        </Button>
      </section>
    </>
  );
}

function MoreLink({ to, label }: { to: "/settings/account" | "/settings/security"; label: string }) {
  return (
    <Link to={to} className="flex min-h-12 items-center justify-between border-t text-foreground no-underline">
      {label}
      <ChevronRight aria-hidden="true" className="size-5 text-muted-foreground" />
    </Link>
  );
}
