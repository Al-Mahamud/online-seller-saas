import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { api, call } from "@/api/client";
import { FormError, PasswordField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { currentPassword, newPassword } from "@/features/auth/schemas";
import { TwoFactorSection } from "./TwoFactorSection";

export function SecurityPage() {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-lg flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("account.security")} />
      </div>
      <PasswordSection />
      <TwoFactorSection />
    </div>
  );
}

const passwordSchema = z.object({ currentPassword, newPassword });

function PasswordSection() {
  const { t } = useTranslation();
  const form = useForm({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: "", newPassword: "" },
  });
  const change = useMutation({
    mutationFn: (body: z.output<typeof passwordSchema>) => call(api.POST("/api/v1/me/password", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      form.reset();
      toast.success(t("account.password.done"));
    },
    onError: (error) => showServerError(form, error, { codes: { "account.wrong_password": "currentPassword" } }),
  });
  return (
    <Section title={t("account.password.title")}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => change.mutate(values))}>
        <FormError form={form} />
        <PasswordField
          form={form}
          name="currentPassword"
          label={t("account.password.current")}
          autoComplete="current-password"
        />
        <PasswordField
          form={form}
          name="newPassword"
          label={t("account.password.new")}
          hint={t("account.password.hint")}
          autoComplete="new-password"
        />
        <Button type="submit" className="self-start" disabled={change.isPending}>
          {t("account.password.submit")}
        </Button>
      </form>
    </Section>
  );
}
