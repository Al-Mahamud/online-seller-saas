import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { resetBusinessData, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { Button } from "@/components/ui/button";

/** Owners hand the business over first. */
export function LeaveBusiness() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const leave = useMutation({
    mutationFn: () => call(api.POST("/api/v1/staff/leave")),
    onSuccess: async () => {
      resetBusinessData(queryClient);
      toast.success(t("staff.left", { name: business.name }));
      await router.navigate({ to: "/" });
      await router.invalidate();
    },
  });
  return (
    <>
      <Button variant="outline" className="self-start" onClick={() => setConfirming(true)}>
        {t("staff.leave")}
      </Button>
      {confirming && (
        <ConfirmDialog
          title={t("staff.leaveTitle", { name: business.name })}
          description={t("staff.leaveDescription")}
          confirm={t("staff.leave")}
          pending={leave.isPending}
          onConfirm={() => leave.mutate()}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
  );
}
