import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { ArrowRight, Check } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { type Me, signedIn } from "@/api/session";
import { FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AuthPage, authButton, BackToSignIn, LabelLink } from "./AuthLayout";
import { appOrRecoveryCode, currentPassword, userId } from "./schemas";

const route = getRouteApi("/_auth/sign-in");

export function SignInPage() {
  const { t } = useTranslation();
  const { redirect, userId: knownUserId, notice } = route.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [secondStep, setSecondStep] = useState(false);
  const [expired, setExpired] = useState<string>();

  async function finish(result: Me | { twoFactorRequired: boolean }) {
    if ("twoFactorRequired" in result) {
      setSecondStep(true);
      return;
    }
    await signedIn(queryClient, result);
    await navigate({ href: redirect ?? "/", replace: true });
  }

  if (secondStep) {
    return (
      <TwoStepForm
        onDone={finish}
        onExpired={(message) => {
          setExpired(message);
          setSecondStep(false);
        }}
      />
    );
  }
  return (
    <AuthPage title={t("auth.signIn.title")} description={t("auth.signIn.subtitle")}>
      {notice === "password-changed" && (
        <Alert role="status" className="rounded-[10px] border-0 bg-accent px-3.5 py-3 text-accent-foreground">
          <Check aria-hidden="true" strokeWidth={2.5} />
          <AlertDescription className="text-[15px] font-semibold text-accent-foreground">
            {t("auth.forgot.done")}
          </AlertDescription>
        </Alert>
      )}
      {expired && (
        <Alert variant="destructive">
          <AlertDescription>{expired}</AlertDescription>
        </Alert>
      )}
      <PasswordForm initialUserId={knownUserId ?? ""} onDone={finish} />
      <div className="mt-auto flex flex-col items-start gap-0.5 border-t pt-4 min-[880px]:mt-2.5 min-[880px]:pt-5">
        <p className="text-[15px] leading-normal text-[#2B342F]">{t("auth.signIn.newHere")}</p>
        <Button variant="link" className="px-0 text-[17px] font-semibold" asChild>
          <Link to="/sign-up" search={{ redirect }}>
            {t("auth.signIn.createAccount")}
            <ArrowRight aria-hidden="true" />
          </Link>
        </Button>
      </div>
    </AuthPage>
  );
}

const passwordSchema = z.object({ userId, password: currentPassword });

function PasswordForm({
  initialUserId,
  onDone,
}: {
  initialUserId: string;
  onDone: (result: Me | { twoFactorRequired: boolean }) => Promise<void>;
}) {
  const { t } = useTranslation();
  const form = useForm({
    resolver: zodResolver(passwordSchema),
    defaultValues: { userId: initialUserId, password: "" },
  });
  const signIn = useMutation({
    mutationFn: (body: z.output<typeof passwordSchema>) => call(api.POST("/api/v1/auth/login", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: onDone,
    onError: (error) => {
      showServerError(form, error);
      form.resetField("password");
    },
  });

  return (
    <form noValidate className="flex flex-col gap-4.5" onSubmit={form.handleSubmit((values) => signIn.mutate(values))}>
      <FormError form={form} strong />
      <TextField
        form={form}
        name="userId"
        label={t("auth.userId.label")}
        labelAction={<LabelLink to="/forgot-user-id">{t("auth.userId.forgot")}</LabelLink>}
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
      />
      <PasswordField
        form={form}
        name="password"
        label={t("auth.password")}
        labelAction={<LabelLink to="/forgot-password">{t("auth.signIn.forgot")}</LabelLink>}
        autoComplete="current-password"
      />
      <Button type="submit" size="lg" className={authButton} disabled={signIn.isPending}>
        {t("auth.signIn.submit")}
      </Button>
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
    <AuthPage title={t("auth.twoStep.title")} description={t("auth.twoStep.description")}>
      <form
        noValidate
        className="flex flex-col gap-4.5"
        onSubmit={form.handleSubmit((values) => confirm.mutate(values))}
      >
        <FormError form={form} strong />
        <TextField
          form={form}
          name="code"
          label={t("auth.twoStep.label")}
          autoComplete="one-time-code"
          spellCheck={false}
        />
        <Button type="submit" size="lg" className={authButton} disabled={confirm.isPending}>
          {t("auth.twoStep.submit")}
        </Button>
      </form>
      <BackToSignIn />
    </AuthPage>
  );
}
