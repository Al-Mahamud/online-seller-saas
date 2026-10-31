import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { AuthPage, authButton, BackToSignIn } from "./AuthLayout";
import { CodeBoxes } from "./CodeBoxes";
import { ResendCode } from "./ResendCode";
import { code, login } from "./schemas";

const contactSchema = z.object({ login });
const codeSchema = z.object({ code });

/** The phone or email, then the code sent to it, then the User ID of the account that proved it. */
export function ForgotUserIdPage() {
  const { t } = useTranslation();
  const [sentTo, setSentTo] = useState<string>();
  const [found, setFound] = useState<string>();
  const contactForm = useForm({ resolver: zodResolver(contactSchema), defaultValues: { login: "" } });
  const codeForm = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });

  const send = useMutation({
    mutationFn: (to: string) => call(api.POST("/api/v1/auth/otp", { body: { to, purpose: "USER_ID_RECOVERY" } })),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, to) => setSentTo(to),
    onError: (error) => {
      if (sentTo) {
        showServerError(codeForm, error);
      } else {
        showServerError(contactForm, error, { fields: { to: "login" } });
      }
    },
  });
  const recover = useMutation({
    mutationFn: (values: { code: string }) =>
      call(api.POST("/api/v1/auth/user-id/recover", { body: { to: sentTo ?? "", code: values.code } })),
    meta: { showsOwnErrors: true },
    onSuccess: (answer) => setFound(answer.userId),
    onError: (error) => showServerError(codeForm, error, { codes: { "otp.invalid": "code" } }),
  });

  if (found) {
    return (
      <AuthPage title={t("auth.userId.yours")} description={t("auth.userId.yoursNote")}>
        <p
          lang="en"
          className="flex min-h-19 items-center justify-center rounded-[10px] bg-accent px-4 py-3 text-center text-[26px] font-bold wrap-anywhere text-accent-foreground"
        >
          {found}
        </p>
        <Button size="lg" className={authButton} asChild>
          <Link to="/sign-in" search={{ userId: found }}>
            {t("auth.signUp.signIn")}
          </Link>
        </Button>
        <Button variant="link" className="self-start px-0 font-semibold" asChild>
          <Link to="/forgot-password">{t("auth.userId.passwordToo")}</Link>
        </Button>
      </AuthPage>
    );
  }
  if (sentTo) {
    return (
      <AuthPage title={t("auth.signUp.codeTitle")} description={t("auth.codeSentIfAccount")}>
        <form
          noValidate
          className="flex flex-col gap-5.5"
          onSubmit={codeForm.handleSubmit((values) => recover.mutate(values))}
        >
          <FormError form={codeForm} strong />
          <CodeBoxes form={codeForm} name="code" label={t("auth.code")} />
          <Button type="submit" size="lg" className={authButton} disabled={recover.isPending}>
            {t("auth.twoStep.submit")}
          </Button>
          <div className="flex flex-col items-start border-t pt-3">
            <ResendCode onResend={() => send.mutate(sentTo)} pending={send.isPending} />
            <Button type="button" variant="link" className="px-0 font-semibold" onClick={() => setSentTo(undefined)}>
              <ArrowLeft aria-hidden="true" />
              {t("auth.useOtherLogin")}
            </Button>
          </div>
        </form>
      </AuthPage>
    );
  }
  return (
    <AuthPage title={t("auth.userId.forgot")} description={t("auth.userId.description")}>
      <form
        noValidate
        className="flex flex-col gap-4.5"
        onSubmit={contactForm.handleSubmit((values) => send.mutate(values.login))}
      >
        <FormError form={contactForm} strong />
        <TextField
          form={contactForm}
          name="login"
          label={t("auth.login")}
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
