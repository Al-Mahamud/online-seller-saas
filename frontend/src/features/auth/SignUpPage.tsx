import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { authOptionsQuery, signedIn } from "@/api/session";
import { CodeField, FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { AuthHeading } from "./AuthLayout";
import { ResendCode } from "./ResendCode";
import { code, name, newPassword, phone } from "./schemas";

const route = getRouteApi("/_auth/sign-up");

const detailsSchema = z
  .object({
    name,
    phone,
    email: z.string().trim(),
    password: newPassword,
    codeSentTo: z.enum(["PHONE", "EMAIL"]),
  })
  .superRefine((values, context) => {
    if (values.codeSentTo === "EMAIL" && !values.email) {
      context.addIssue({ code: "custom", path: ["email"], message: "field.required" });
    }
  });
type Details = z.output<typeof detailsSchema>;

const codeSchema = z.object({ code });

/** Who they are, then the code that proves the phone or email. */
export function SignUpPage() {
  const { t } = useTranslation();
  const { redirect } = route.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: options } = useQuery(authOptionsQuery);
  const channels = options?.otpChannels ?? [];
  const [details, setDetails] = useState<Details>();

  const detailsForm = useForm({
    resolver: zodResolver(detailsSchema),
    defaultValues: { name: "", phone: "", email: "", password: "", codeSentTo: "PHONE" as "PHONE" | "EMAIL" },
  });
  const codeForm = useForm({ resolver: zodResolver(codeSchema), defaultValues: { code: "" } });
  const chosen = detailsForm.watch("codeSentTo");

  // With one way of receiving codes there is nothing to choose (no SMS until a gateway is set up).
  const onlyChannel = channels.length === 1 ? channels[0] : undefined;
  useEffect(() => {
    if (onlyChannel) {
      detailsForm.setValue("codeSentTo", onlyChannel);
    }
  }, [onlyChannel, detailsForm]);

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
      const taken = error instanceof ApiError && ["account.phone_taken", "account.email_taken"].includes(error.code);
      if (taken || (error instanceof ApiError && error.fields.some((field) => field.field !== "code"))) {
        setDetails(undefined);
        showServerError(detailsForm, error, {
          codes: { "account.phone_taken": "phone", "account.email_taken": "email" },
        });
      } else {
        showServerError(codeForm, error, { codes: { "otp.invalid": "code" } });
      }
    },
  });

  if (details) {
    return (
      <>
        <AuthHeading title={t("auth.signUp.codeTitle")}>
          {t("auth.signUp.codeSent", { to: details.codeSentTo === "PHONE" ? details.phone : details.email })}
        </AuthHeading>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={codeForm.handleSubmit((values) => create.mutate(values))}
        >
          <FormError form={codeForm} />
          <CodeField form={codeForm} name="code" label={t("auth.code")} />
          <Button type="submit" size="lg" disabled={create.isPending}>
            {t("auth.signUp.submit")}
          </Button>
          <div className="flex flex-wrap justify-between gap-2">
            <ResendCode onResend={() => send.mutate(details)} pending={send.isPending} />
            <Button type="button" variant="link" className="px-0" onClick={() => setDetails(undefined)}>
              {t("auth.signUp.editDetails")}
            </Button>
          </div>
        </form>
      </>
    );
  }
  return (
    <>
      <AuthHeading title={t("auth.signUp.title")}>{t("auth.signUp.subtitle")}</AuthHeading>
      {options && channels.length === 0 ? (
        <Alert variant="destructive">
          <AlertDescription>{t("auth.signUp.unavailable")}</AlertDescription>
        </Alert>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={detailsForm.handleSubmit((values) => send.mutate(values))}
        >
          <FormError form={detailsForm} />
          <TextField form={detailsForm} name="name" label={t("auth.signUp.name")} autoComplete="name" />
          <TextField form={detailsForm} name="phone" label={t("auth.phone")} type="tel" autoComplete="tel" />
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
            <fieldset className="flex flex-col gap-1.5">
              <legend className="mb-1.5 text-[15px] font-semibold">{t("auth.signUp.codeTo")}</legend>
              <div className="flex gap-5">
                <Label className="min-h-11 text-base font-normal">
                  <input
                    type="radio"
                    value="PHONE"
                    className="size-5 accent-primary"
                    {...detailsForm.register("codeSentTo")}
                  />
                  {t("auth.signUp.codeToPhone")}
                </Label>
                <Label className="min-h-11 text-base font-normal">
                  <input
                    type="radio"
                    value="EMAIL"
                    className="size-5 accent-primary"
                    {...detailsForm.register("codeSentTo")}
                  />
                  {t("auth.email")}
                </Label>
              </div>
            </fieldset>
          )}
          <Button type="submit" size="lg" disabled={send.isPending || !options}>
            {t("auth.signUp.continue")}
          </Button>
        </form>
      )}
      <p className="mt-auto flex flex-wrap items-center gap-1.5 text-[15px]">
        {t("auth.signUp.haveAccount")}
        <Button variant="link" className="px-0" asChild>
          <Link to="/sign-in" search={{ redirect }}>
            {t("auth.signUp.signIn")}
          </Link>
        </Button>
      </p>
    </>
  );
}
