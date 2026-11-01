import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { Section } from "@/components/layout/Section";
import { ProfileFrame } from "@/features/account/ProfileFrame";
import { preferencesQuery } from "./api";

/** The low-stock digest is the only email that can be turned off; emails link here to do it. */
export function NotificationSettingsPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data } = useQuery(preferencesQuery);
  // Shows the choice at once; a refusal puts it back.
  const [choosing, setChoosing] = useState<boolean>();
  const save = useMutation({
    mutationFn: (lowStockDigestEmail: boolean) =>
      call(api.PUT("/api/v1/notifications/preferences", { body: { lowStockDigestEmail } })),
    onSuccess: (saved) => {
      queryClient.setQueryData(preferencesQuery.queryKey, saved);
      toast.success(t("notifications.saved"));
    },
    onSettled: () => setChoosing(undefined),
  });
  return (
    <ProfileFrame>
      <div className="flex max-w-xl flex-col gap-3">
        <Section title={t("notifications.emails")}>
          {data && (
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                className="size-5 flex-none accent-primary"
                checked={choosing ?? data.lowStockDigestEmail}
                onChange={(event) => {
                  setChoosing(event.target.checked);
                  save.mutate(event.target.checked);
                }}
              />
              {t("notifications.lowStockDigest")}
            </label>
          )}
          <p className="text-sm text-muted-foreground">{t("notifications.alwaysSent")}</p>
        </Section>
      </div>
    </ProfileFrame>
  );
}
