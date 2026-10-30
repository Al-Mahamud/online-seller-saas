import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { businessSelected } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { businessFields } from "./schemas";

const schema = z.object(businessFields);

/** The new business is selected at once, and the caller is its owner. */
export function CreateBusinessForm({ onCreated }: { onCreated?: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const router = useRouter();
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { name: "", phone: "", address: "" } });
  const create = useMutation({
    mutationFn: (values: z.output<typeof schema>) =>
      call(
        api.POST("/api/v1/businesses", {
          body: { name: values.name, phone: values.phone || undefined, address: values.address || undefined },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: async (business) => {
      businessSelected(queryClient, business);
      onCreated?.();
      await router.navigate({ to: "/" });
      await router.invalidate();
    },
    onError: (error) => showServerError(form, error),
  });
  return (
    <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => create.mutate(values))}>
      <FormError form={form} />
      <TextField form={form} name="name" label={t("business.name")} autoComplete="organization" />
      <TextField form={form} name="phone" label={t("business.phone")} type="tel" />
      <TextField form={form} name="address" label={t("business.address")} autoComplete="street-address" />
      <Button type="submit" size="lg" disabled={create.isPending}>
        {t("business.create")}
      </Button>
    </form>
  );
}
