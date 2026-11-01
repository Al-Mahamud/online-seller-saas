import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { api, call } from "@/api/client";
import { FormError, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { LedgerDialog } from "@/components/layout/LedgerDialog";
import { PlacePicker, type PlaceValue } from "@/features/delivery/PlacePicker";
import { latinDigits } from "@/lib/digits";
import { type Customer, customerQuery } from "./api";

const schema = z.object({
  name: z.string().check(z.trim(), z.minLength(1, "field.required"), z.maxLength(200, "field.nameLong")),
  phone: z.pipe(z.string().check(z.trim(), z.minLength(1, "field.required")), z.transform(latinDigits)),
  altPhone: z.pipe(z.string().check(z.trim()), z.transform(latinDigits)),
  address: z.string().check(z.trim(), z.maxLength(1000, "field.addressLong")),
  note: z.string().check(z.trim(), z.maxLength(2000, "field.footerLong")),
});

/** A new customer, or `customer`'s details. */
export default function CustomerDialog({
  customer,
  onSaved,
  onClose,
}: {
  customer?: Customer;
  onSaved: (customer: Customer) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [place, setPlace] = useState<PlaceValue>({
    districtId: customer?.district?.id ?? null,
    areaId: customer?.area?.id ?? null,
  });
  const form = useForm({
    resolver: zodResolver(schema),
    defaultValues: {
      name: customer?.name ?? "",
      phone: customer?.phone ?? "",
      altPhone: customer?.altPhone ?? "",
      address: customer?.address ?? "",
      note: customer?.note ?? "",
    },
  });
  const save = useMutation({
    mutationFn: (values: z.output<typeof schema>) => {
      const location = { districtId: place.districtId ?? undefined, areaId: place.areaId ?? undefined };
      return customer
        ? call(
            api.PATCH("/api/v1/customers/{id}", {
              params: { path: { id: customer.id } },
              // An empty box clears that detail.
              body: { ...values, ...location, clearLocation: place.districtId === null, version: customer.version },
            }),
          )
        : call(
            api.POST("/api/v1/customers", {
              body: {
                name: values.name,
                phone: values.phone,
                altPhone: values.altPhone || undefined,
                address: values.address || undefined,
                note: values.note || undefined,
                ...location,
              },
            }),
          );
    },
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      queryClient.setQueryData(customerQuery(saved.id).queryKey, saved);
      void queryClient.invalidateQueries({ queryKey: ["customers"] });
      onSaved(saved);
    },
    onError: (error) => {
      showServerError(form, error, { codes: { "customer.phone_taken": "phone" } });
      if (customer) {
        void queryClient.invalidateQueries(customerQuery(customer.id));
      }
    },
  });
  return (
    <LedgerDialog
      title={customer ? t("customers.edit") : t("customers.new")}
      description={customer ? customer.name : t("customers.formHint")}
      action={t("customers.save")}
      pending={save.isPending}
      onSubmit={() => void form.handleSubmit((values) => save.mutate(values))()}
      onClose={onClose}
    >
      <FormError form={form} />
      <div className="grid gap-4 md:grid-cols-2 md:gap-3">
        <TextField form={form} name="name" label={t("customers.name")} autoComplete="off" />
        <TextField form={form} name="phone" label={t("customers.phone")} type="tel" autoComplete="off" />
      </div>
      <TextField form={form} name="altPhone" label={t("customers.altPhone")} type="tel" autoComplete="off" />
      <TextAreaField form={form} name="address" label={t("customers.address")} />
      <PlacePicker value={place} onChange={setPlace} />
      <TextAreaField form={form} name="note" label={t("customers.note")} hint={t("customers.noteHint")} />
    </LedgerDialog>
  );
}
