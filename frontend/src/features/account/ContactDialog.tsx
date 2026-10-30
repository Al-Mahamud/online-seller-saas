import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { meQuery, useMe } from "@/api/session";
import { CodeField, FormError, PasswordField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ResendCode } from "@/features/auth/ResendCode";
import { code, currentPassword, login } from "@/features/auth/schemas";

export type ContactKind = "phone" | "email";

const destinationSchema = z.object({ to: login });
const confirmationSchema = z.object({ code, currentPassword });

/** Adds, proves or replaces the phone or email: a code sent there, then the code and the password. */
export function ContactDialog({ kind, onClose }: { kind: ContactKind; onClose: () => void }) {
  const { t } = useTranslation();
  const me = useMe();
  const queryClient = useQueryClient();
  const [sentTo, setSentTo] = useState<string>();
  const current = kind === "phone" ? me.phone : me.email;
  const verified = kind === "phone" ? me.phoneVerified : me.emailVerified;
  const destinationForm = useForm({
    resolver: zodResolver(destinationSchema),
    // Proving what is already there needs nothing typed.
    defaultValues: { to: current && !verified ? current : "" },
  });
  const confirmationForm = useForm({
    resolver: zodResolver(confirmationSchema),
    defaultValues: { code: "", currentPassword: "" },
  });

  const send = useMutation({
    mutationFn: (to: string) => call(api.POST("/api/v1/me/contact/code", { body: { to } })),
    meta: { showsOwnErrors: true },
    onSuccess: (_data, to) => setSentTo(to),
    onError: (error) => {
      if (sentTo) {
        showServerError(confirmationForm, error);
      } else {
        showServerError(destinationForm, error);
      }
    },
  });
  const confirm = useMutation({
    mutationFn: (values: z.output<typeof confirmationSchema>) =>
      call(api.POST("/api/v1/me/contact", { body: { ...values, to: sentTo ?? "" } })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      queryClient.setQueryData(meQuery.queryKey, updated);
      toast.success(t("account.saved"));
      onClose();
    },
    onError: (error) => {
      // Someone else has it: the number or address has to change.
      if (error instanceof ApiError && ["account.phone_taken", "account.email_taken"].includes(error.code)) {
        setSentTo(undefined);
        destinationForm.setError("to", { type: "server", message: error.detail });
      } else {
        showServerError(confirmationForm, error, {
          codes: { "otp.invalid": "code", "account.wrong_password": "currentPassword" },
        });
      }
    },
  });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {kind === "phone" ? t("account.contactDialog.phoneTitle") : t("account.contactDialog.emailTitle")}
          </DialogTitle>
          <DialogDescription>
            {sentTo ? t("account.contactDialog.sent", { to: sentTo }) : t("account.contactDialog.description")}
          </DialogDescription>
        </DialogHeader>
        {sentTo ? (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={confirmationForm.handleSubmit((values) => confirm.mutate(values))}
          >
            <FormError form={confirmationForm} />
            <CodeField form={confirmationForm} name="code" label={t("auth.code")} />
            <PasswordField
              form={confirmationForm}
              name="currentPassword"
              label={t("account.contactDialog.currentPassword")}
              autoComplete="current-password"
            />
            <ResendCode onResend={() => send.mutate(sentTo)} pending={send.isPending} />
            <Button type="submit" disabled={confirm.isPending}>
              {t("account.contactDialog.confirm")}
            </Button>
          </form>
        ) : (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={destinationForm.handleSubmit((values) => send.mutate(values.to))}
          >
            <FormError form={destinationForm} />
            <TextField
              form={destinationForm}
              name="to"
              label={kind === "phone" ? t("account.contactDialog.newPhone") : t("account.contactDialog.newEmail")}
              type={kind === "phone" ? "tel" : "email"}
              autoComplete={kind === "phone" ? "tel" : "email"}
            />
            <Button type="submit" disabled={send.isPending}>
              {t("auth.sendCode")}
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
