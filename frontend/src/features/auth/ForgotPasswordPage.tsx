import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { CodeField, FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AuthHeading } from "./AuthLayout";
import { ResendCode } from "./ResendCode";
import { code, login, newPassword } from "./schemas";

const loginSchema = z.object({ login });
const resetSchema = z.object({ code, newPassword });

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [sentTo, setSentTo] = useState<string>();
  const loginForm = useForm({ resolver: zodResolver(loginSchema), defaultValues: { login: "" } });
  const resetForm = useForm({ resolver: zodResolver(resetSchema), defaultValues: { code: "", newPassword: "" } });

  const send = useMutation({
    mutationFn: (to: string) => call(api.POST("/api/v1/auth/otp", { body: { to, purpose: "PASSWORD_RESET" } })),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, to) => setSentTo(to),
    onError: (error) => {
      if (sentTo) {
        showServerError(resetForm, error);
      } else {
        showServerError(loginForm, error, { fields: { to: "login" } });
      }
    },
  });
  const reset = useMutation({
    mutationFn: (values: z.output<typeof resetSchema>) =>
      call(api.POST("/api/v1/auth/password/reset", { body: { ...values, login: sentTo ?? "" } })),
    meta: { showsOwnErrors: true },
    onSuccess: async () => {
      toast.success(t("auth.forgot.done"));
      await navigate({ to: "/sign-in" });
    },
    onError: (error) => showServerError(resetForm, error, { codes: { "otp.invalid": "code" } }),
  });

  return (
    <>
      <AuthHeading title={t("auth.forgot.title")}>{sentTo ? undefined : t("auth.forgot.description")}</AuthHeading>
      {sentTo ? (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={resetForm.handleSubmit((values) => reset.mutate(values))}
        >
          <Alert>
            <AlertDescription>{t("auth.codeSentIfAccount")}</AlertDescription>
          </Alert>
          <FormError form={resetForm} />
          <CodeField form={resetForm} name="code" label={t("auth.code")} />
          <PasswordField
            form={resetForm}
            name="newPassword"
            label={t("auth.forgot.newPassword")}
            hint={t("auth.signUp.passwordHint")}
            autoComplete="new-password"
          />
          <Button type="submit" size="lg" disabled={reset.isPending}>
            {t("auth.forgot.submit")}
          </Button>
          <div className="flex flex-wrap justify-between gap-2">
            <ResendCode onResend={() => send.mutate(sentTo)} pending={send.isPending} />
            <Button type="button" variant="link" className="px-0" onClick={() => setSentTo(undefined)}>
              {t("auth.useOtherLogin")}
            </Button>
          </div>
        </form>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={loginForm.handleSubmit((values) => send.mutate(values.login))}
        >
          <FormError form={loginForm} />
          <TextField form={loginForm} name="login" label={t("auth.login")} autoComplete="username" />
          <Button type="submit" size="lg" disabled={send.isPending}>
            {t("auth.sendCode")}
          </Button>
        </form>
      )}
      <Button variant="link" className="self-start px-0" asChild>
        <Link to="/sign-in">{t("auth.forgot.backToSignIn")}</Link>
      </Button>
    </>
  );
}
