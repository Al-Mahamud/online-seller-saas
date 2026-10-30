import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link, type LinkProps, useRouter } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, resetBusinessData, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { useMe } from "@/api/session";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
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
            <MoreLink to="/settings/categories" label={t("categories.title")} />
            <MoreLink to="/settings/staff" label={t("staff.title")} />
            {hasRole(business, "OWNER") && <MoreLink to="/settings/audit-log" label={t("audit.title")} />}
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
      {!hasRole(business, "OWNER") && <LeaveBusiness />}
    </div>
  );
}

/** Owners hand the business over first. */
function LeaveBusiness() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const leave = useMutation({
    mutationFn: () => call(api.POST("/api/v1/staff/leave")),
    onSuccess: async () => {
      resetBusinessData(queryClient);
      toast.success(t("staff.left", { name: business.name }));
      await router.navigate({ to: "/" });
      await router.invalidate();
    },
  });
  return (
    <>
      <Button variant="outline" className="self-start" onClick={() => setConfirming(true)}>
        {t("staff.leave")}
      </Button>
      {confirming && (
        <ConfirmDialog
          title={t("staff.leaveTitle", { name: business.name })}
          description={t("staff.leaveDescription")}
          confirm={t("staff.leave")}
          pending={leave.isPending}
          onConfirm={() => leave.mutate()}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
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
