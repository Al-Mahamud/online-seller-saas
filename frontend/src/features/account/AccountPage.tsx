import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { api, call } from "@/api/client";
import { authOptionsQuery, meQuery, useMe } from "@/api/session";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { useChangeLanguage } from "@/components/layout/LanguageToggle";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { name } from "@/features/auth/schemas";
import { currentLanguage, type Language } from "@/i18n";
import { cn } from "@/lib/utils";
import { ContactDialog, type ContactKind } from "./ContactDialog";

export function AccountPage() {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-lg flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("account.title")} />
      </div>
      <ProfileSection />
      <LanguageSection />
      <ContactSection />
    </div>
  );
}

const profileSchema = z.object({ name });

function ProfileSection() {
  const { t } = useTranslation();
  const me = useMe();
  const queryClient = useQueryClient();
  const form = useForm({ resolver: zodResolver(profileSchema), values: { name: me.name } });
  const save = useMutation({
    mutationFn: (body: { name: string }) => call(api.PATCH("/api/v1/me", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (updated) => {
      queryClient.setQueryData(meQuery.queryKey, updated);
      toast.success(t("account.saved"));
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <Section title={t("account.profile")}>
      <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
        <FormError form={form} />
        <TextField form={form} name="name" label={t("account.name")} autoComplete="name" />
        <Button type="submit" className="self-start" disabled={save.isPending}>
          {t("account.save")}
        </Button>
      </form>
    </Section>
  );
}

const LANGUAGES: { value: Language; name: string }[] = [
  { value: "bn", name: "বাংলা" },
  { value: "en", name: "English" },
];

function LanguageSection() {
  const { t } = useTranslation();
  const changeLanguage = useChangeLanguage();
  const current = currentLanguage();
  return (
    <Section title={t("account.language")}>
      <fieldset
        aria-label={t("account.language")}
        className="flex w-fit overflow-hidden rounded-lg border border-input"
      >
        {LANGUAGES.map((language) => (
          <button
            key={language.value}
            type="button"
            lang={language.value}
            aria-pressed={current === language.value}
            className={cn(
              "min-h-11 cursor-pointer px-5",
              current === language.value ? "bg-foreground font-semibold text-background" : "bg-card text-foreground",
            )}
            onClick={() => void changeLanguage(language.value)}
          >
            {language.name}
          </button>
        ))}
      </fieldset>
    </Section>
  );
}

function ContactSection() {
  const { t } = useTranslation();
  const me = useMe();
  const { data: options } = useQuery(authOptionsQuery);
  const [editing, setEditing] = useState<ContactKind>();
  const channels = options?.otpChannels ?? [];
  return (
    <Section title={t("account.contact")}>
      <ContactRow
        label={t("account.phone")}
        value={me.phone}
        verified={me.phoneVerified}
        available={channels.includes("PHONE")}
        unavailableText={t("account.smsUnavailable")}
        onEdit={() => setEditing("phone")}
      />
      <ContactRow
        label={t("account.email")}
        value={me.email}
        verified={me.emailVerified}
        available={channels.includes("EMAIL")}
        unavailableText={t("account.emailUnavailable")}
        onEdit={() => setEditing("email")}
      />
      {editing && <ContactDialog kind={editing} onClose={() => setEditing(undefined)} />}
    </Section>
  );
}

function ContactRow({
  label,
  value,
  verified,
  available,
  unavailableText,
  onEdit,
}: {
  label: string;
  value: string | null;
  verified: boolean;
  available: boolean;
  unavailableText: string;
  onEdit: () => void;
}) {
  const { t } = useTranslation();
  const action = !value ? t("account.add") : verified ? t("account.change") : t("account.verify");
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3 first-of-type:border-t-0 first-of-type:pt-0">
      <div className="min-w-0">
        <p className="text-sm text-muted-foreground">{label}</p>
        <p className="font-medium break-all">{value ?? t("account.noEmail")}</p>
        {value && (
          <span
            className={cn(
              "mt-1 inline-block rounded-full px-2 text-xs font-semibold",
              verified ? "bg-accent text-accent-foreground" : "bg-warning text-warning-foreground",
            )}
          >
            {verified ? t("account.verified") : t("account.notVerified")}
          </span>
        )}
      </div>
      {available ? (
        <Button variant="outline" aria-label={`${action}: ${label}`} onClick={onEdit}>
          {action}
        </Button>
      ) : (
        <p className="text-sm text-muted-foreground">{unavailableText}</p>
      )}
    </div>
  );
}
