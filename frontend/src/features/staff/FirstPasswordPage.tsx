import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { businessQuery } from "@/api/business";
import { api, call } from "@/api/client";
import { meQuery, useMe } from "@/api/session";
import { FormError, PasswordField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";
import { AuthHeading, SimpleFrame } from "@/features/auth/AuthLayout";
import { newPassword } from "@/features/auth/schemas";

const schema = z.object({ newPassword, again: z.string() }).check(
  z.refine((values) => values.newPassword === values.again, {
    path: ["again"],
    message: "firstLogin.mismatch",
  }),
);

/**
 * Signed in with the starting password a business gave: their own password, before anything
 * else. The server lets nothing else through until then.
 */
export function FirstPasswordPage() {
  const { t } = useTranslation();
  const me = useMe();
  const router = useRouter();
  const queryClient = useQueryClient();
  const signOut = useSignOut();
  const { data: business } = useQuery(businessQuery);
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { newPassword: "", again: "" } });
  const save = useMutation({
    mutationFn: (values: { newPassword: string }) =>
      call(api.POST("/api/v1/me/first-password", { body: { newPassword: values.newPassword } })),
    meta: { showsOwnErrors: true },
    onSuccess: async (updated) => {
      queryClient.setQueryData(meQuery.queryKey, updated);
      await queryClient.invalidateQueries(businessQuery);
      await router.navigate({ to: "/" });
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <SimpleFrame>
      <AuthHeading title={t("firstLogin.title", { name: me.name })}>{t("firstLogin.subtitle")}</AuthHeading>
      {business && (
        <p role="status" className="rounded-[12px] bg-accent px-3.5 py-3 text-[15px] text-accent-foreground">
          {t("firstLogin.added", { business: business.name, role: t(`business.role.${business.role}`) })}
        </p>
      )}
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
        <FormError form={form} strong />
        <PasswordField
          form={form}
          name="newPassword"
          label={t("firstLogin.newPassword")}
          hint={t("firstLogin.hint")}
          autoComplete="new-password"
        />
        <PasswordField form={form} name="again" label={t("firstLogin.again")} autoComplete="new-password" />
        <Button type="submit" size="lg" disabled={save.isPending}>
          {t("firstLogin.save")}
        </Button>
      </form>
      <p className="text-sm text-muted-foreground">{t("firstLogin.after", { userId: me.userId })}</p>
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
