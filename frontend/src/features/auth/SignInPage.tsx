import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { authOptionsQuery, type Me, signedIn } from "@/api/session";
import { CodeField, FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AuthHeading } from "./AuthLayout";
import { ResendCode } from "./ResendCode";
import { appOrRecoveryCode, code, currentPassword, login } from "./schemas";

const route = getRouteApi("/_auth/sign-in");

type Step = "password" | "code" | "two-step";

export function SignInPage() {
  const { t } = useTranslation();
  const { redirect } = route.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: options } = useQuery(authOptionsQuery);
  const [step, setStep] = useState<Step>("password");
  const [typedLogin, setTypedLogin] = useState("");
  const [expired, setExpired] = useState<string>();

  async function finish(result: Me | { twoFactorRequired: boolean }) {
    if ("twoFactorRequired" in result) {
      setStep("two-step");
      return;
    }
    await signedIn(queryClient, result);
    await navigate({ href: redirect ?? "/", replace: true });
  }

  if (step === "two-step") {
    return (
      <TwoStepForm
        onDone={finish}
        onExpired={(message) => {
          setExpired(message);
          setStep("password");
        }}
      />
    );
  }
  const offersCodes = (options?.otpChannels.length ?? 0) > 0;
  return (
    <>
      <AuthHeading title={t("auth.signIn.title")}>{t("auth.signIn.subtitle")}</AuthHeading>
      {expired && (
        <Alert variant="destructive">
          <AlertDescription>{expired}</AlertDescription>
        </Alert>
      )}
      {step === "password" ? (
        <PasswordForm
          initialLogin={typedLogin}
          onDone={finish}
          offersCodes={offersCodes}
          onUseCode={(value) => {
            setTypedLogin(value);
            setStep("code");
          }}
        />
      ) : (
        <CodeForm
          initialLogin={typedLogin}
          onDone={finish}
          onUsePassword={(value) => {
            setTypedLogin(value);
            setStep("password");
          }}
        />
      )}
      <div className="mt-auto flex flex-col gap-2.5 rounded-xl border bg-card p-4">
        <p className="text-[15px] text-[#2B342F]">{t("auth.signIn.newHere")}</p>
        <Button variant="outline" size="lg" asChild>
          <Link to="/sign-up" search={{ redirect }}>
            {t("auth.signIn.createAccount")}
          </Link>
        </Button>
      </div>
    </>
  );
}

const passwordSchema = z.object({ login, password: currentPassword });

function PasswordForm({
  initialLogin,
  offersCodes,
  onDone,
  onUseCode,
}: {
  initialLogin: string;
  offersCodes: boolean;
  onDone: (result: Me | { twoFactorRequired: boolean }) => Promise<void>;
  onUseCode: (login: string) => void;
}) {
  const { t } = useTranslation();
  const form = useForm({
    resolver: zodResolver(passwordSchema),
    defaultValues: { login: initialLogin, password: "" },
  });
  const signIn = useMutation({
    mutationFn: (body: z.output<typeof passwordSchema>) => call(api.POST("/api/v1/auth/login", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: onDone,
    onError: (error) => showServerError(form, error),
  });

  return (
    <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => signIn.mutate(values))}>
      <FormError form={form} />
      <TextField form={form} name="login" label={t("auth.login")} autoComplete="username" />
      <PasswordField form={form} name="password" label={t("auth.password")} autoComplete="current-password" />
      <Button type="submit" size="lg" disabled={signIn.isPending}>
        {t("auth.signIn.submit")}
      </Button>
      <div className="flex flex-wrap justify-between gap-2 text-[15px]">
        {offersCodes && (
          <Button type="button" variant="link" className="px-0" onClick={() => onUseCode(form.getValues("login"))}>
            {t("auth.signIn.withCode")}
          </Button>
        )}
        <Button variant="link" className="px-0" asChild>
          <Link to="/forgot-password">{t("auth.signIn.forgot")}</Link>
        </Button>
      </div>
    </form>
  );
}

const loginOnlySchema = z.object({ login });
const codeSchema = z.object({ code });

function CodeForm({
  initialLogin,
  onDone,
  onUsePassword,
}: {
  initialLogin: string;
  onDone: (result: Me | { twoFactorRequired: boolean }) => Promise<void>;
  onUsePassword: (login: string) => void;
}) {
  const { t } = useTranslation();
  const [sentTo, setSentTo] = useState<string>();
  const loginForm = useForm({ resolver: zodResolver(loginOnlySchema), defaultValues: { login: initialLogin } });
  const codeForm = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });

  const send = useMutation({
    mutationFn: (to: string) => call(api.POST("/api/v1/auth/otp", { body: { to, purpose: "LOGIN" } })),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, to) => setSentTo(to),
    onError: (error) => {
      if (sentTo) {
        showServerError(codeForm, error);
      } else {
        showServerError(loginForm, error, { fields: { to: "login" } });
      }
    },
  });
  const signIn = useMutation({
    mutationFn: (values: { code: string }) =>
      call(api.POST("/api/v1/auth/login", { body: { login: sentTo ?? "", code: values.code } })),
    meta: { showsOwnErrors: true },
    onSuccess: onDone,
    onError: (error) => showServerError(codeForm, error, { codes: { "otp.invalid": "code" } }),
  });

  if (!sentTo) {
    return (
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
        <Button
          type="button"
          variant="link"
          className="self-start px-0"
          onClick={() => onUsePassword(loginForm.getValues("login"))}
        >
          {t("auth.signIn.withPassword")}
        </Button>
      </form>
    );
  }
  return (
    <form
      noValidate
      className="flex flex-col gap-4"
      onSubmit={codeForm.handleSubmit((values) => signIn.mutate(values))}
    >
      <Alert>
        <AlertDescription>{t("auth.codeSentIfAccount")}</AlertDescription>
      </Alert>
      <FormError form={codeForm} />
      <CodeField form={codeForm} name="code" label={t("auth.code")} />
      <Button type="submit" size="lg" disabled={signIn.isPending}>
        {t("auth.signIn.submit")}
      </Button>
      <div className="flex flex-wrap justify-between gap-2">
        <ResendCode onResend={() => send.mutate(sentTo)} pending={send.isPending} />
        <Button type="button" variant="link" className="px-0" onClick={() => setSentTo(undefined)}>
          {t("auth.useOtherLogin")}
        </Button>
      </div>
    </form>
  );
}

const twoStepSchema = z.object({ code: appOrRecoveryCode });

/** The second step: a code from the authenticator app, or one of the recovery codes. */
function TwoStepForm({
  onDone,
  onExpired,
}: {
  onDone: (result: Me) => Promise<void>;
  onExpired: (message: string) => void;
}) {
  const { t } = useTranslation();
  const form = useForm({ resolver: zodResolver(twoStepSchema), defaultValues: { code: "" } });
  const confirm = useMutation({
    mutationFn: (body: { code: string }) => call(api.POST("/api/v1/auth/login/two-factor", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: onDone,
    onError: (error) => {
      // Five minutes passed, or too many tries: this sign-in has to start again.
      if (error instanceof ApiError && error.code === "auth.two_factor_expired") {
        onExpired(error.detail);
      } else {
        showServerError(form, error, { codes: { "two_factor.invalid_code": "code" } });
      }
    },
  });

  return (
    <>
      <AuthHeading title={t("auth.twoStep.title")}>{t("auth.twoStep.description")}</AuthHeading>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => confirm.mutate(values))}>
        <FormError form={form} />
        <TextField form={form} name="code" label={t("auth.twoStep.label")} autoComplete="one-time-code" />
        <Button type="submit" size="lg" disabled={confirm.isPending}>
          {t("auth.twoStep.submit")}
        </Button>
      </form>
    </>
  );
}
