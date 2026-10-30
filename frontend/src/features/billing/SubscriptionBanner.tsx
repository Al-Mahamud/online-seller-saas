import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { useFormat } from "@/lib/format";
import { subscriptionStatusQuery } from "./status";

const WARN_TRIAL_DAYS = 3;
const DAY = 24 * 60 * 60_000;

/** On every page when the subscription needs someone's attention, with the way to pay for those who can. */
export function SubscriptionBanner() {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const { data } = useQuery(subscriptionStatusQuery);
  const canSeeBilling = hasRole(business, "MANAGER");
  let message: string | undefined;
  switch (data?.status) {
    case "TRIALING":
      if (data.trialEndsAt && new Date(data.trialEndsAt).getTime() - Date.now() <= WARN_TRIAL_DAYS * DAY) {
        message = t("billing.banner.trialEnding", { date: format.date(data.trialEndsAt) });
      }
      break;
    case "GRACE":
      message = t("billing.banner.grace", { date: format.date(data.graceEndsAt ?? data.currentPeriodEnd ?? "") });
      break;
    case "READ_ONLY":
      message = canSeeBilling ? t("billing.banner.readOnly") : t("billing.banner.readOnlyStaff");
      break;
    case "SUSPENDED":
      message = t("billing.banner.suspended");
      break;
  }
  if (!message) {
    return null;
  }
  return (
    <div
      role="status"
      className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 bg-warning px-4 py-2 text-warning-foreground md:px-10"
    >
      <p>{message}</p>
      {canSeeBilling && data?.status !== "SUSPENDED" && (
        <Link to="/billing" className="flex min-h-11 items-center font-semibold text-warning-foreground underline">
          {t("billing.banner.action")}
        </Link>
      )}
    </div>
  );
}
