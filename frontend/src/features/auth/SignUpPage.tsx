import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { Check } from "lucide-react";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { authOptionsQuery, signedIn } from "@/api/session";
import { FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { AuthPage, authButton, StepBar } from "./AuthLayout";
import { CodeBoxes } from "./CodeBoxes";
import { ResendCode } from "./ResendCode";
import { code, name, newPassword, newUserId, phone } from "./schemas";

const route = getRouteApi("/_auth/sign-up");

const detailsSchema = z
  .object({
    name,
    phone,
    userId: newUserId,
    email: z.string().check(z.trim()),
    password: newPassword,
    codeSentTo: z.enum(["PHONE", "EMAIL"]),
  })
  .check(
    z.refine((values) => values.codeSentTo !== "EMAIL" || !!values.email, {
      path: ["email"],
      message: "field.required",
    }),
  );
type Details = z.output<typeof detailsSchema>;

const codeSchema = z.object({ code });

const CHANNEL_CARD =
  "min-h-13 rounded-[10px] border-[1.5px] border-input px-3 text-base font-semibold has-checked:border-primary has-checked:bg-accent has-focus-visible:ring-3 has-focus-visible:ring-ring/50";

/** Who they are, then the code that proves the phone or email. */
export function SignUpPage() {
  const { t } = useTranslation();
  const { redirect } = route.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: options } = useQuery(authOptionsQuery);
  const channels = options?.otpChannels ?? [];
  const [details, setDetails] = useState<Details>();
  // The last answer about a User ID: it stands only while that ID is what's in the box.
  const [checked, setChecked] = useState<{ userId: string; available: boolean }>();

  const detailsForm = useForm({
    resolver: zodResolver(detailsSchema),
    defaultValues: {
      name: "",
      phone: "",
      userId: "",
      email: "",
      password: "",
      codeSentTo: "PHONE" as "PHONE" | "EMAIL",
    },
  });
  const codeForm = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });
  const chosen = detailsForm.watch("codeSentTo");
  const typedUserId = detailsForm.watch("userId").trim();
  const userIdFree = checked?.userId === typedUserId && checked.available;
  const userIdError = detailsForm.getFieldState("userId", detailsForm.formState).error;

  // With one way of receiving codes there is nothing to choose (no SMS until a gateway is set up).
  const onlyChannel = channels.length === 1 ? channels[0] : undefined;
  useEffect(() => {
    if (onlyChannel) {
      detailsForm.setValue("codeSentTo", onlyChannel);
    }
  }, [onlyChannel, detailsForm]);

  const check = useMutation({
    mutationFn: (userId: string) =>
      call(api.GET("/api/v1/auth/user-id/availability", { params: { query: { userId } } })),
    meta: { showsOwnErrors: true },
    onSuccess: ({ available }, userId) => {
      setChecked({ userId, available });
      if (!available) {
        detailsForm.setError("userId", { type: "server", message: "auth.userId.taken" });
      }
    },
    onError: (error) => showServerError(detailsForm, error),
  });

  /** Pressing the check button is optional: going on checks too, so nobody spends a code on a taken ID. */
  async function userIdIsFree(userId: string) {
    if (checked?.userId === userId) {
      if (!checked.available) {
        detailsForm.setError("userId", { type: "server", message: "auth.userId.taken" });
      }
      return checked.available;
    }
    try {
      return (await check.mutateAsync(userId)).available;
    } catch {
      return false;
    }
  }

  const send = useMutation({
    mutationFn: (values: Details) =>
      call(
        api.POST("/api/v1/auth/otp", {
          body: { to: values.codeSentTo === "PHONE" ? values.phone : values.email, purpose: "SIGNUP" },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, values) => setDetails(values),
    onError: (error, values) => {
      if (details) {
        showServerError(codeForm, error);
      } else {
        showServerError(detailsForm, error, { fields: { to: values.codeSentTo === "PHONE" ? "phone" : "email" } });
      }
    },
  });
  const create = useMutation({
    mutationFn: (values: { code: string }) =>
      call(
        api.POST("/api/v1/auth/signup", {
          body: { ...(details as Details), email: details?.email || undefined, code: values.code },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: async (me) => {
      await signedIn(queryClient, me);
      await navigate({ href: redirect ?? "/", replace: true });
    },
    onError: (error) => {
      // A problem with the details, not the code: back to them, with the problem under its field.
      const taken =
        error instanceof ApiError &&
        ["account.phone_taken", "account.email_taken", "account.user_id_taken"].includes(error.code);
      if (taken || (error instanceof ApiError && error.fields.some((field) => field.field !== "code"))) {
        setDetails(undefined);
        setChecked(undefined);
        showServerError(detailsForm, error, {
          codes: {
            "account.phone_taken": "phone",
            "account.email_taken": "email",
            "account.user_id_taken": "userId",
          },
        });
      } else {
        showServerError(codeForm, error, { codes: { "otp.invalid": "code" } });
      }
    },
  });

  if (details) {
    return (
      <AuthPage
        title={t("auth.signUp.codeTitle")}
        description={t("auth.signUp.codeSent", { to: details.codeSentTo === "PHONE" ? details.phone : details.email })}
      >
        <StepBar step={2} />
        <form
          noValidate
          className="flex flex-col gap-5.5"
          onSubmit={codeForm.handleSubmit((values) => create.mutate(values))}
        >
          <FormError form={codeForm} strong />
          <CodeBoxes form={codeForm} name="code" label={t("auth.code")} />
          <Button type="submit" size="lg" className={authButton} disabled={create.isPending}>
            {t("auth.signUp.submit")}
          </Button>
          <div className="flex flex-col items-start border-t pt-3">
            <ResendCode onResend={() => send.mutate(details)} pending={send.isPending} />
            <Button type="button" variant="link" className="px-0 font-semibold" onClick={() => setDetails(undefined)}>
              {t("auth.signUp.editDetails")}
            </Button>
          </div>
        </form>
      </AuthPage>
    );
  }
  return (
    <AuthPage title={t("auth.signUp.title")} description={t("auth.signUp.subtitle")} wide>
      <StepBar step={1} />
      {options && channels.length === 0 ? (
        <Alert variant="destructive">
          <AlertDescription>{t("auth.signUp.unavailable")}</AlertDescription>
        </Alert>
      ) : (
        <form
          noValidate
          className="grid items-start gap-4 min-[880px]:grid-cols-2"
          onSubmit={detailsForm.handleSubmit(async (values) => {
            if (await userIdIsFree(values.userId)) {
              send.mutate(values);
            }
          })}
        >
          <div className="empty:hidden min-[880px]:col-span-2">
            <FormError form={detailsForm} strong />
          </div>
          <TextField form={detailsForm} name="name" label={t("auth.signUp.name")} autoComplete="name" />
          <TextField form={detailsForm} name="phone" label={t("auth.phone")} type="tel" autoComplete="tel" />
          <div className="min-w-0 min-[880px]:col-span-2">
            <TextField
              form={detailsForm}
              name="userId"
              label={t("auth.userId.label")}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={20}
              errorIcon
              onInput={(event) => {
                event.currentTarget.value = event.currentTarget.value.toLowerCase();
                detailsForm.clearErrors("userId");
              }}
              hint={
                userIdError ? undefined : userIdFree ? (
                  <span className="flex items-start gap-1.5 font-semibold text-primary">
                    <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2.5} />
                    {t("auth.userId.available")}
                  </span>
                ) : (
                  t("auth.userId.rules")
                )
              }
              beside={
                <Button
                  type="button"
                  variant="outline"
                  className="h-13 w-29 rounded-[10px] border-[1.5px] border-primary bg-card px-0 font-semibold text-primary hover:text-primary"
                  disabled={check.isPending}
                  onClick={async () => {
                    if (await detailsForm.trigger("userId")) {
                      check.mutate(typedUserId);
                    }
                  }}
                >
                  {check.isPending ? t("auth.userId.checking") : t("auth.userId.check")}
                </Button>
              }
            />
            <p role="status" className="sr-only">
              {userIdFree ? t("auth.userId.available") : ""}
            </p>
          </div>
          <TextField
            form={detailsForm}
            name="email"
            label={chosen === "EMAIL" ? t("auth.email") : t("auth.signUp.emailOptional")}
            type="email"
            autoComplete="email"
          />
          <PasswordField
            form={detailsForm}
            name="password"
            label={t("auth.password")}
            hint={t("auth.signUp.passwordHint")}
            autoComplete="new-password"
          />
          {channels.length > 1 && (
            <fieldset className="flex flex-col gap-1.5 min-[880px]:col-span-2">
              <legend className="mb-1.5 text-[15px] font-semibold">{t("auth.signUp.codeTo")}</legend>
              <div className="grid grid-cols-2 gap-2.5">
                <Label className={CHANNEL_CARD}>
                  <input
                    type="radio"
                    value="PHONE"
                    className="size-4.5 shrink-0 accent-primary"
                    {...detailsForm.register("codeSentTo")}
                  />
                  {t("auth.signUp.codeToPhone")}
                </Label>
                <Label className={CHANNEL_CARD}>
                  <input
                    type="radio"
                    value="EMAIL"
                    className="size-4.5 shrink-0 accent-primary"
                    {...detailsForm.register("codeSentTo")}
                  />
                  {t("auth.email")}
                </Label>
              </div>
            </fieldset>
          )}
          <Button
            type="submit"
            size="lg"
            className={`${authButton} min-[880px]:col-span-2`}
            disabled={send.isPending || check.isPending || !options}
          >
            {t("auth.signUp.continue")}
          </Button>
        </form>
      )}
      <p className="mt-auto flex flex-wrap items-center gap-1.5 text-[15px] text-[#2B342F]">
        {t("auth.signUp.haveAccount")}
        <Button variant="link" className="px-0 font-semibold" asChild>
          <Link to="/sign-in" search={{ redirect }}>
            {t("auth.signUp.signIn")}
          </Link>
        </Button>
      </p>
    </AuthPage>
  );
}
