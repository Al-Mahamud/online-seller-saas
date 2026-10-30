import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { businessQuery, settingsQuery, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { latinDigits } from "@/lib/digits";
import { businessFields } from "./schemas";

export function BusinessPage({ children }: { children?: React.ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-lg flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("business.title")} />
      </div>
      <ProfileSection />
      <LogoSection />
      <SettingsSection />
      {children}
    </div>
  );
}

const profileSchema = z.object(businessFields);

function ProfileSection() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const form = useForm({
    resolver: zodResolver(profileSchema),
    values: { name: business.name, phone: business.phone ?? "", address: business.address ?? "" },
  });
  const save = useMutation({
    // Empty clears the phone or address.
    mutationFn: (body: z.output<typeof profileSchema>) => call(api.PATCH("/api/v1/business", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      queryClient.setQueryData(businessQuery.queryKey, updated);
      toast.success(t("account.saved"));
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <Section title={t("business.profile.title")}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
        <FormError form={form} />
        <TextField form={form} name="name" label={t("business.name")} autoComplete="organization" />
        <TextField form={form} name="phone" label={t("business.phone")} type="tel" />
        <TextField form={form} name="address" label={t("business.address")} autoComplete="street-address" />
        <Button type="submit" className="self-start" disabled={save.isPending}>
          {t("account.save")}
        </Button>
      </form>
    </Section>
  );
}

function LogoSection() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const update = (updated: typeof business) => queryClient.setQueryData(businessQuery.queryKey, updated);
  const upload = useMutation({
    mutationFn: async (file: File) => {
      const data = new FormData();
      data.append("file", file);
      // The generated type calls a file a string; the browser sends the form data as it is.
      const uploaded = await call(api.POST("/api/v1/uploads", { body: { file: "" }, bodySerializer: () => data }));
      return call(api.PUT("/api/v1/business/logo", { body: { fileId: uploaded.id } }));
    },
    onSuccess: update,
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/business/logo")),
    onSuccess: update,
  });
  const busy = upload.isPending || remove.isPending;
  return (
    <Section title={t("business.profile.logo")}>
      {business.logo && (
        <img
          src={business.logo.small}
          alt={t("business.profile.logoAlt")}
          className="size-20 rounded-xl object-cover"
        />
      )}
      <p className="text-sm text-muted-foreground">{t("business.profile.logoHint")}</p>
      <div className="flex flex-wrap gap-2">
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          aria-label={t("business.profile.upload")}
          className="sr-only"
          tabIndex={-1}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              upload.mutate(file);
            }
            event.target.value = "";
          }}
        />
        <Button variant="outline" disabled={busy} onClick={() => input.current?.click()}>
          {business.logo ? t("business.profile.change") : t("business.profile.upload")}
        </Button>
        {business.logo && (
          <Button variant="outline" disabled={busy} onClick={() => remove.mutate()}>
            {t("business.profile.remove")}
          </Button>
        )}
      </div>
    </Section>
  );
}

const settingsSchema = z.object({
  stockCommitPoint: z.enum(["SHIP", "CONFIRM"]),
  lowStockDefault: z
    .string()
    .trim()
    .transform(latinDigits)
    .pipe(z.string().regex(/^\d{1,6}$/, "field.numberRange"))
    .transform(Number)
    .pipe(z.number().max(100_000, "field.numberRange")),
  orderNumberPrefix: z
    .string()
    .trim()
    .transform((text) => text.toUpperCase())
    .pipe(z.string().regex(/^[A-Z0-9-]{0,10}$/, "field.prefixInvalid")),
  invoiceFooter: z.string().trim().max(500, "field.footerLong"),
});

function SettingsSection() {
  const { t } = useTranslation();
  const { data: settings } = useQuery(settingsQuery);
  const queryClient = useQueryClient();
  const form = useForm({
    resolver: zodResolver(settingsSchema),
    values: settings && { ...settings, lowStockDefault: String(settings.lowStockDefault) },
  });
  const save = useMutation({
    mutationFn: (body: z.output<typeof settingsSchema>) => call(api.PATCH("/api/v1/business/settings", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      queryClient.setQueryData(settingsQuery.queryKey, updated);
      toast.success(t("account.saved"));
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <Section title={t("business.settings.title")}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
        <FormError form={form} />
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-[15px] font-semibold">{t("business.settings.stockQuestion")}</legend>
          {(["SHIP", "CONFIRM"] as const).map((point) => (
            <Label key={point} className="items-start gap-2.5 py-1.5 text-base leading-normal font-normal">
              <input
                type="radio"
                value={point}
                className="mt-1 size-5 shrink-0 accent-primary"
                {...form.register("stockCommitPoint")}
              />
              <span>
                <span className="block font-medium">{t(`business.settings.${point}`)}</span>
                <span className="block text-sm text-muted-foreground">{t(`business.settings.${point}Hint`)}</span>
              </span>
            </Label>
          ))}
        </fieldset>
        <TextField
          form={form}
          name="lowStockDefault"
          label={t("business.settings.lowStock")}
          hint={t("business.settings.lowStockHint")}
          inputMode="numeric"
        />
        <TextField
          form={form}
          name="orderNumberPrefix"
          label={t("business.settings.prefix")}
          hint={t("business.settings.prefixHint")}
          maxLength={10}
          autoCapitalize="characters"
        />
        <TextAreaField
          form={form}
          name="invoiceFooter"
          label={t("business.settings.footer")}
          hint={t("business.settings.footerHint")}
        />
        <Button type="submit" className="self-start" disabled={save.isPending || !settings}>
          {t("account.save")}
        </Button>
      </form>
    </Section>
  );
}
