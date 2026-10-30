import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { type Business, businessQuery, hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, PasswordField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { currentPassword } from "@/features/auth/schemas";

const schema = z.object({ password: currentPassword });

/** Owners: the business goes in 7 days, and until then the banner offers the undo. */
export function DeletionSection() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { password: "" } });
  const request = useMutation({
    mutationFn: (body: { password: string }) => call(api.POST("/api/v1/business/deletion", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: ({ deletionDueAt }) => {
      const updated: Business = { ...business, deletionDueAt };
      queryClient.setQueryData(businessQuery.queryKey, updated);
      setOpen(false);
    },
    onError: (error) => showServerError(form, error, { codes: { "account.wrong_password": "password" } }),
  });
  if (!hasRole(business, "OWNER") || business.deletionDueAt) {
    return null;
  }
  return (
    <Section title={t("business.deletion.title")}>
      <p className="text-muted-foreground">{t("business.deletion.description")}</p>
      <Button variant="destructive" className="self-start" onClick={() => setOpen(true)}>
        {t("business.deletion.title")}
      </Button>
      {open && (
        <Dialog open onOpenChange={setOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t("business.deletion.confirmTitle", { name: business.name })}</DialogTitle>
              <DialogDescription>{t("business.deletion.confirm")}</DialogDescription>
            </DialogHeader>
            <form
              noValidate
              className="flex flex-col gap-4"
              onSubmit={form.handleSubmit((values) => request.mutate(values))}
            >
              <FormError form={form} />
              <PasswordField form={form} name="password" label={t("auth.password")} autoComplete="current-password" />
              <Button type="submit" variant="destructive" disabled={request.isPending}>
                {t("business.deletion.title")}
              </Button>
            </form>
          </DialogContent>
        </Dialog>
      )}
    </Section>
  );
}
