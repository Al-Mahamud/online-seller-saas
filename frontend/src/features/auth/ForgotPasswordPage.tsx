import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { AuthPage, authButton, BackToSignIn, LabelLink } from "./AuthLayout";
import { CodeBoxes } from "./CodeBoxes";
import { ResendCode } from "./ResendCode";
import { code, newPassword, userId } from "./schemas";

const userIdSchema = z.object({ userId });
const resetSchema = z.object({ code, newPassword });

/** The User ID, then the code sent to that account's phone or email with a new password. */
export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [sentFor, setSentFor] = useState<string>();
  const userIdForm = useForm({ resolver: zodResolver(userIdSchema), defaultValues: { userId: "" } });
  const resetForm = useForm({ resolver: zodResolver(resetSchema), defaultValues: { code: "", newPassword: "" } });

  const send = useMutation({
    mutationFn: (value: string) => call(api.POST("/api/v1/auth/password/code", { body: { userId: value } })),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, value) => setSentFor(value),
    onError: (error) => {
      if (sentFor) {
        showServerError(resetForm, error);
      } else {
        showServerError(userIdForm, error);
      }
    },
  });
  const reset = useMutation({
    mutationFn: (values: z.output<typeof resetSchema>) =>
      call(api.POST("/api/v1/auth/password/reset", { body: { ...values, userId: sentFor ?? "" } })),
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      await navigate({ to: "/sign-in", search: { userId: sentFor, notice: "password-changed" } });
    },
    onError: (error) => showServerError(resetForm, error, { codes: { "otp.invalid": "code" } }),
  });

  if (sentFor) {
    return (
      <AuthPage title={t("auth.forgot.newPassword")} description={t("auth.forgot.codeSent")}>
        <form
          noValidate
          className="flex flex-col gap-4.5"
          onSubmit={resetForm.handleSubmit((values) => reset.mutate(values))}
        >
          <FormError form={resetForm} strong />
          <CodeBoxes form={resetForm} name="code" label={t("auth.code")} />
          <PasswordField
            form={resetForm}
            name="newPassword"
            label={t("auth.forgot.newPassword")}
            hint={t("auth.signUp.passwordHint")}
            autoComplete="new-password"
          />
          <Button type="submit" size="lg" className={authButton} disabled={reset.isPending}>
            {t("auth.forgot.submit")}
          </Button>
          <div className="flex flex-col items-start border-t pt-2.5">
            <ResendCode onResend={() => send.mutate(sentFor)} pending={send.isPending} />
            <BackToSignIn />
          </div>
        </form>
      </AuthPage>
    );
  }
  return (
    <AuthPage title={t("auth.forgot.title")} description={t("auth.forgot.description")}>
      <form
        noValidate
        className="flex flex-col gap-4.5"
        onSubmit={userIdForm.handleSubmit((values) => send.mutate(values.userId))}
      >
        <FormError form={userIdForm} strong />
        <TextField
          form={userIdForm}
          name="userId"
          label={t("auth.userId.label")}
          labelAction={<LabelLink to="/forgot-user-id">{t("auth.userId.forgot")}</LabelLink>}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
        />
        <Button type="submit" size="lg" className={authButton} disabled={send.isPending}>
          {t("auth.sendCode")}
        </Button>
      </form>
      <BackToSignIn />
    </AuthPage>
  );
}
