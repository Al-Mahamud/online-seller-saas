import { useQuery } from "@tanstack/react-query";
import { createFileRoute, redirect } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { businessQuery } from "@/api/business";
import { meQuery } from "@/api/session";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";
import { AuthHeading, SimpleFrame } from "@/features/auth/AuthLayout";
import { CreateBusinessForm } from "@/features/business/CreateBusinessForm";

/** The first run: signed in, with no business yet. */
export const Route = createFileRoute("/welcome")({
  beforeLoad: async ({ context }) => {
    if (!(await context.queryClient.ensureQueryData(meQuery))) {
      throw redirect({ to: "/sign-in" });
    }
    if (await context.queryClient.ensureQueryData(businessQuery)) {
      throw redirect({ to: "/" });
    }
  },
  component: Welcome,
});

function Welcome() {
  const { t } = useTranslation();
  const signOut = useSignOut();
  useQuery(businessQuery);
  return (
    <SimpleFrame>
      <AuthHeading title={t("business.welcome.title")}>{t("business.welcome.subtitle")}</AuthHeading>
      <CreateBusinessForm />
      <p className="text-sm text-muted-foreground">{t("business.welcome.invited")}</p>
      <Button
        variant="link"
        className="mt-auto self-start px-0"
        disabled={signOut.isPending}
        onClick={() => signOut.mutate()}
      >
        {t("account.signOut")}
      </Button>
    </SimpleFrame>
  );
}
