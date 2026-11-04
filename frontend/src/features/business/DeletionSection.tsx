import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod/mini";
import { type Business, businessQuery, hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { FormError, PasswordField } from "@/components/form/fields";
import { showServerError } from "@/components/form/serverErrors";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { currentPassword } from "@/features/auth/schemas";
import { productsQuery } from "@/features/catalog/api";
import { customersQuery } from "@/features/customers/api";
import { ordersQuery } from "@/features/orders/api";
import { useFormat } from "@/lib/format";

const schema = z.object({ password: currentPassword });

/** Owners: the business goes in 7 days, and until then the banner offers the undo. */
export function DeletionSection({ id }: { id: string }) {
  const { t } = useTranslation();
  const business = useBusiness();
  const [open, setOpen] = useState(false);
  if (!hasRole(business, "OWNER") || business.deletionDueAt) {
    return null;
  }
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className="flex scroll-mt-4 flex-wrap items-center gap-3.5 rounded-2xl border-[1.5px] border-[#F2C9C3] bg-card p-3.5 md:p-5"
    >
      <div className="min-w-0 flex-[1_1_280px]">
        <h2 id={`${id}-title`} className="text-xl font-bold text-danger-ink">
          {t("business.deletion.title")}
        </h2>
        <p className="text-sm text-[#3E4842]">{t("business.deletion.description")}</p>
      </div>
      <Button
        variant="outline"
        className="h-11 border-[1.5px] border-destructive px-4 text-[15px] font-bold text-destructive hover:bg-danger-soft hover:text-destructive"
        onClick={() => setOpen(true)}
      >
        {t("business.deletion.title")}
      </Button>
      {open && <ConfirmDeletion business={business} onClose={() => setOpen(false)} />}
    </section>
  );
}

function ConfirmDeletion({ business, onClose }: { business: Business; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const products = useQuery(productsQuery({ size: 1 }));
  const orders = useQuery(ordersQuery({ size: 1 }));
  const customers = useQuery(customersQuery("", { size: 1 }));
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { password: "" } });
  const request = useMutation({
    mutationFn: (body: { password: string }) => call(api.POST("/api/v1/business/deletion", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: ({ deletionDueAt }) => {
      const updated: Business = { ...business, deletionDueAt };
      queryClient.setQueryData(businessQuery.queryKey, updated);
      onClose();
    },
    onError: (error) => showServerError(form, error, { codes: { "account.wrong_password": "password" } }),
  });
  const counted = products.data && orders.data && customers.data;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-md">
        <form noValidate onSubmit={form.handleSubmit((values) => request.mutate(values))}>
          <div className="flex flex-col gap-2.5 p-4.5">
            <span aria-hidden="true" className="flex size-11 items-center justify-center rounded-xl bg-danger-soft">
              <Trash2 className="size-5.5 text-destructive" />
            </span>
            <DialogTitle className="text-xl font-bold">
              {t("business.deletion.confirmTitle", { name: business.name })}
            </DialogTitle>
            <DialogDescription asChild>
              <ul className="list-disc pl-4.5 text-sm leading-relaxed text-[#3E4842]">
                {counted && (
                  <li>
                    {t("business.deletion.counts", {
                      products: format.number(products.data.totalItems),
                      orders: format.number(orders.data.totalItems),
                      customers: format.number(customers.data.totalItems),
                    })}
                  </li>
                )}
                <li>{t("business.deletion.later")}</li>
                <li>{t("business.deletion.account")}</li>
              </ul>
            </DialogDescription>
            <FormError form={form} />
            <PasswordField
              form={form}
              name="password"
              label={t("business.deletion.password")}
              autoComplete="current-password"
            />
          </div>
          <div className="flex justify-end gap-2 border-t bg-background px-4.5 pt-3 pb-4">
            <Button type="button" variant="outline" className="h-11 px-4 font-bold" onClick={onClose}>
              {t("business.deletion.cancel")}
            </Button>
            <Button type="submit" variant="destructive" className="h-11 px-4 font-bold" disabled={request.isPending}>
              {t("business.deletion.delete")}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
