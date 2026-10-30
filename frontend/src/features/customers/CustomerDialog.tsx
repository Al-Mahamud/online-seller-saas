import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { api, call } from "@/api/client";
import { FormError, TextAreaField, TextField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PlacePicker, type PlaceValue } from "@/features/delivery/PlacePicker";
import { latinDigits } from "@/lib/digits";
import { type Customer, customerQuery } from "./api";

const schema = z.object({
  name: z.string().trim().min(1, "field.required").max(200, "field.nameLong"),
  phone: z.string().trim().min(1, "field.required").transform(latinDigits),
  altPhone: z.string().trim().transform(latinDigits),
  address: z.string().trim().max(1000, "field.addressLong"),
  note: z.string().trim().max(2000, "field.footerLong"),
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
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{customer ? t("customers.edit") : t("customers.new")}</DialogTitle>
        </DialogHeader>
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
          <FormError form={form} />
          <TextField form={form} name="name" label={t("customers.name")} autoComplete="off" />
          <TextField form={form} name="phone" label={t("customers.phone")} type="tel" autoComplete="off" />
          <TextField form={form} name="altPhone" label={t("customers.altPhone")} type="tel" autoComplete="off" />
          <TextAreaField form={form} name="address" label={t("customers.address")} />
          <PlacePicker value={place} onChange={setPlace} />
          <TextAreaField form={form} name="note" label={t("customers.note")} hint={t("customers.noteHint")} />
          <Button type="submit" disabled={save.isPending}>
            {t("customers.save")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
