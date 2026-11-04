import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Clock, Lock, TriangleAlert } from "lucide-react";
import { useTranslation } from "react-i18next";
import { can, useBusiness } from "@/api/business";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { billingQuery } from "./api";
import { subscriptionStatusQuery } from "./status";

const WARN_TRIAL_DAYS = 3;
const DAY = 24 * 60 * 60_000;
const NEAR_LIMIT = 0.9;

const LOOK = {
  mint: {
    box: "border-mint-line bg-accent text-accent-foreground",
    button: "bg-primary text-primary-foreground hover:bg-[#0a5c44]",
  },
  amber: {
    box: "border-[#F2D3B0] bg-warning text-[#7A3F08]",
    button: "bg-warning-foreground text-white hover:bg-[#713d09]",
  },
  red: {
    box: "border-[#F2C6C1] bg-danger-soft text-danger-ink",
    button: "bg-destructive text-white hover:bg-[#931d13]",
  },
} as const;

/**
 * On every page when the plan needs someone's attention: payment due, read-only, suspended, an old
 * trial ending, or this month's orders nearly used up on a plan with a limit. With the way to the
 * plan page for those who can open it.
 */
export function SubscriptionBanner() {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const { data } = useQuery(subscriptionStatusQuery);
  const canSeeBilling = can(business, "SETTINGS");
  // Usage is only for managers and owners, who can do something about it.
  const { data: billing } = useQuery({ ...billingQuery, enabled: canSeeBilling && data?.status === "ACTIVE" });
  let banner: { text: string; look: keyof typeof LOOK; icon: typeof Clock; action?: string } | undefined;
  switch (data?.status) {
    case "TRIALING":
      if (data.trialEndsAt && new Date(data.trialEndsAt).getTime() - Date.now() <= WARN_TRIAL_DAYS * DAY) {
        banner = {
          text: t("billing.banner.trialEnding", { date: format.date(data.trialEndsAt) }),
          look: "mint",
          icon: Clock,
        };
      }
      break;
    case "ACTIVE": {
      const orders = billing?.usage.orders_per_month;
      if (orders?.limit && orders.used / orders.limit >= NEAR_LIMIT) {
        banner = {
          text: t("billing.banner.nearLimit", { used: format.number(orders.used), limit: format.number(orders.limit) }),
          look: "mint",
          icon: Clock,
          action: t("billing.banner.action"),
        };
      }
      break;
    }
    case "GRACE":
      banner = {
        text: t("billing.banner.grace", { date: format.date(data.graceEndsAt ?? data.currentPeriodEnd ?? "") }),
        look: "amber",
        icon: TriangleAlert,
        action: t("billing.banner.payNow"),
      };
      break;
    case "READ_ONLY":
      banner = canSeeBilling
        ? { text: t("billing.banner.readOnly"), look: "red", icon: Lock, action: t("billing.banner.reopen") }
        : { text: t("billing.banner.readOnlyStaff"), look: "red", icon: Lock };
      break;
    case "SUSPENDED":
      banner = { text: t("billing.banner.suspended"), look: "red", icon: Lock };
      break;
  }
  if (!banner) {
    return null;
  }
  const look = LOOK[banner.look];
  const Icon = banner.icon;
  return (
    <div className="px-4 pt-3 md:px-10">
      <div
        role="status"
        className={cn("flex flex-wrap items-center gap-x-3.5 gap-y-2 rounded-2xl border py-2.5 pr-3 pl-3.5", look.box)}
      >
        <span aria-hidden="true" className="flex size-8.5 flex-none items-center justify-center rounded-[10px] bg-card">
          <Icon className="size-4.5" strokeWidth={2.2} />
        </span>
        <p className="min-w-0 flex-[1_1_320px] text-[15px] font-semibold">{banner.text}</p>
        {canSeeBilling && banner.action && (
          <Link
            to="/billing"
            className={cn(
              "flex h-10 flex-none items-center rounded-[10px] px-4 text-[15px] font-bold no-underline hover:text-white",
              look.button,
            )}
          >
            {banner.action}
          </Link>
        )}
      </div>
    </div>
  );
}
