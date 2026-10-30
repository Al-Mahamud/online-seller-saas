import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouter } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { resetBusinessData } from "@/api/business";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { meQuery } from "@/api/session";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";
import { AuthHeading, SimpleFrame } from "@/features/auth/AuthLayout";
import { useFormat } from "@/lib/format";
import { clearPendingInvite, pendingInviteToken } from "./pendingInvite";

/** The link in an invite: which business and role, then signing in or up, then joining. */
export function InvitePage() {
  const { t } = useTranslation();
  const format = useFormat();
  const router = useRouter();
  const queryClient = useQueryClient();
  const signOut = useSignOut();
  const [token] = useState(pendingInviteToken);
  const { data: me } = useQuery(meQuery);
  const preview = useQuery({
    queryKey: ["invite-preview", token],
    queryFn: () => call(api.POST("/api/v1/public/invites/lookup", { body: { token: token ?? "" } })),
    enabled: !!token,
    meta: { showsOwnErrors: true },
  });
  const join = useMutation({
    mutationFn: () => call(api.POST("/api/v1/invites/accept", { body: { token: token ?? "" } })),
    meta: { showsOwnErrors: true },
    onSuccess: async (joined) => {
      clearPendingInvite();
      resetBusinessData(queryClient);
      toast.success(t("invite.joined", { name: joined.businessName }));
      await router.navigate({ to: "/" });
      await router.invalidate();
    },
  });

  if (!token || preview.isError) {
    return (
      <SimpleFrame>
        <AuthHeading title={t("invite.title")} />
        <Alert variant="destructive">
          <AlertDescription>{preview.error ? errorText(preview.error) : t("invite.noLink")}</AlertDescription>
        </Alert>
        <Button variant="outline" size="lg" asChild>
          <Link to="/">{t("page.backToToday")}</Link>
        </Button>
      </SimpleFrame>
    );
  }
  if (!preview.data) {
    return <SimpleFrame>{null}</SimpleFrame>;
  }
  const { businessName, role, invitedBy, sentTo, expiresAt } = preview.data;
  return (
    <SimpleFrame>
      <AuthHeading title={t("invite.heading", { name: businessName })}>
        {t("invite.details", { invitedBy, role: t(`business.role.${role}`) })}
      </AuthHeading>
      <p className="text-sm text-muted-foreground">{t("invite.expires", { date: format.date(expiresAt) })}</p>
      {join.error && (
        <Alert variant="destructive">
          <AlertDescription>{errorText(join.error)}</AlertDescription>
        </Alert>
      )}
      {me ? (
        <div className="flex flex-col gap-3">
          <Button size="lg" disabled={join.isPending} onClick={() => join.mutate()}>
            {t("invite.join", { name: businessName })}
          </Button>
          <p className="text-sm text-muted-foreground">{t("invite.signedInAs", { name: me.name, sentTo })}</p>
          <Button
            variant="link"
            className="self-start px-0"
            disabled={signOut.isPending}
            onClick={() => signOut.mutate()}
          >
            {t("account.signOut")}
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p>{t("invite.signInFirst", { sentTo })}</p>
          <Button size="lg" asChild>
            <Link to="/sign-up" search={{ redirect: "/invites" }}>
              {t("auth.signIn.createAccount")}
            </Link>
          </Button>
          <Button variant="outline" size="lg" asChild>
            <Link to="/sign-in" search={{ redirect: "/invites" }}>
              {t("auth.signUp.signIn")}
            </Link>
          </Button>
        </div>
      )}
    </SimpleFrame>
  );
}
