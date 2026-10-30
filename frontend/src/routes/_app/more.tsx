import { createFileRoute, Link, type LinkProps } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { useMe } from "@/api/session";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";

export const Route = createFileRoute("/_app/more")({
  component: More,
});

/** Everything that isn't a tab: the business and the account; customers, billing and the rest from F5 onwards. */
function More() {
  const { t } = useTranslation();
  const me = useMe();
  const business = useBusiness();
  const signOut = useSignOut();
  return (
    <div className="flex max-w-md flex-col gap-4">
      <PageHeader title={t("nav.more")} />
      {hasRole(business, "MANAGER") && (
        <section className="rounded-xl border bg-card px-4 py-1">
          <nav aria-label={t("business.title")} className="flex flex-col">
            <MoreLink to="/settings/business" label={t("business.title")} />
          </nav>
        </section>
      )}
      <section className="flex flex-col gap-3 rounded-xl border bg-card p-4">
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
    </div>
  );
}

function MoreLink({ to, label }: { to: LinkProps["to"]; label: string }) {
  return (
    <Link
      to={to}
      className="flex min-h-12 items-center justify-between border-t text-foreground no-underline first:border-t-0"
    >
      {label}
      <ChevronRight aria-hidden="true" className="size-5 text-muted-foreground" />
    </Link>
  );
}
