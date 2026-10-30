import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { type Business, businessQuery, hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";

/** On every page while the business is due to be deleted. */
export function DeletionBanner() {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/business/deletion")),
    onSuccess: () => {
      const updated: Business = { ...business, deletionDueAt: null };
      queryClient.setQueryData(businessQuery.queryKey, updated);
    },
  });
  if (!business.deletionDueAt) {
    return null;
  }
  return (
    <div
      role="status"
      className="flex flex-wrap items-center justify-between gap-2 bg-warning px-4 py-2 text-warning-foreground md:px-10"
    >
      <p>{t("business.deletion.banner", { name: business.name, date: format.date(business.deletionDueAt) })}</p>
      {hasRole(business, "OWNER") && (
        <Button variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
          {t("business.deletion.undo")}
        </Button>
      )}
    </div>
  );
}
