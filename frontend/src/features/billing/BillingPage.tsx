import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Check, Minus, TriangleAlert } from "lucide-react";
import { lazy, Suspense, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { BackToMore } from "@/components/layout/PageHeader";
import { Pagination } from "@/components/layout/Pagination";
import { ON_DARK_FOCUS } from "@/lib/focus";
import { useFormat } from "@/lib/format";
import type { Paging } from "@/lib/paging";
import { cn } from "@/lib/utils";
import {
  type Billing,
  billingChanged,
  billingQuery,
  type Invoice,
  invoicesQuery,
  type Plan,
  plansQuery,
  usePlanName,
} from "./api";
import { daysUntil, MONTH_CHOICES, type Months, monthsPrice } from "./plans";

const PayDialog = lazy(() => import("./PayDialog").then((module) => ({ default: module.PayDialog })));

const USAGE = ["variants", "orders_per_month", "staff_seats"] as const;
const NEAR_LIMIT = 0.9;
const CARD = "rounded-2xl border bg-card";

type Paying = { plan: Plan; upgrade: boolean; months?: Months };

const isFree = (plan: Plan) => plan.priceMonthly === 0;

/**
 * The plan, what it allows and how much of it is used, the plans to move to (the owner), and the
 * invoices. A paid plan runs for the months paid; Free never ends.
 */
export function BillingPage() {
  const { t } = useTranslation();
  const business = useBusiness();
  const { data: billing } = useQuery(billingQuery);
  const { data: plans } = useQuery(plansQuery);
  const [paying, setPaying] = useState<Paying>();
  const [months, setMonths] = useState<Months>(12);
  const owner = hasRole(business, "OWNER");
  const canPay = owner && !!billing && billing.status !== "SUSPENDED";
  const sorted = [...(plans ?? [])].sort((a, b) => a.priceMonthly - b.priceMonthly);
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the plan share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("billing.title")}</h1>
        </div>
        {billing && (
          <PlanBand
            billing={billing}
            plans={sorted}
            canPay={canPay}
            onPay={(plan) => setPaying({ plan, upgrade: false, months })}
          />
        )}
      </div>
      <div className="flex flex-col gap-4 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5">
        {billing && <Usage billing={billing} />}
        {billing && canPay && (
          <Plans
            billing={billing}
            plans={sorted}
            months={months}
            onMonths={setMonths}
            onPay={(plan, upgrade) => setPaying({ plan, upgrade, months })}
          />
        )}
        <Invoices />
      </div>
      {paying && (
        <Suspense fallback={null}>
          <PayDialog
            plan={paying.plan}
            upgrade={paying.upgrade}
            initialMonths={paying.months ?? 12}
            billing={billing}
            onClose={() => setPaying(undefined)}
          />
        </Suspense>
      )}
    </div>
  );
}

/** The plan now, its state and dates, and the one thing to do about it. */
function PlanBand({
  billing,
  plans,
  canPay,
  onPay,
}: {
  billing: Billing;
  plans: Plan[];
  canPay: boolean;
  onPay: (plan: Plan) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const queryClient = useQueryClient();
  const free = isFree(billing.plan) && billing.status === "ACTIVE";
  const pending = plans.find((plan) => plan.code === billing.pendingPlanCode);
  const keep = useMutation({
    mutationFn: () => call(api.PUT("/api/v1/billing/plan", { body: { planCode: billing.plan.code } })),
    onSuccess: (updated) => {
      queryClient.setQueryData(billingQuery.queryKey, updated);
      billingChanged(queryClient);
      toast.success(t("billing.downgradeCancelled"));
    },
  });
  const status = billing.status;
  const pill = {
    ACTIVE: "bg-accent text-accent-foreground",
    TRIALING: "bg-marigold-text text-ledger",
    GRACE: "bg-warning text-warning-foreground",
    READ_ONLY: "bg-danger-soft text-danger-ink",
    SUSPENDED: "bg-danger-soft text-danger-ink",
  }[status as "ACTIVE"];
  const days = billing.currentPeriodEnd ? daysUntil(billing.currentPeriodEnd) : undefined;
  const graceDays = billing.graceEndsAt ? daysUntil(billing.graceEndsAt) : undefined;
  const dateLine = free
    ? t("billing.free")
    : status === "TRIALING" && billing.trialEndsAt
      ? t("billing.trialUntil", { date: format.date(billing.trialEndsAt) })
      : status === "ACTIVE" && billing.currentPeriodEnd
        ? t("billing.paidUntil", { date: format.date(billing.currentPeriodEnd) })
        : status === "GRACE" && billing.graceEndsAt
          ? t("billing.graceUntil", { date: format.date(billing.graceEndsAt) })
          : status === "READ_ONLY"
            ? t("billing.readOnlySince")
            : undefined;
  const meter =
    status === "GRACE" && graceDays !== undefined
      ? { share: graceDays / 7, line: t("billing.graceDaysLeft", { count: format.number(graceDays) }), warn: true }
      : status === "ACTIVE" && !free && days !== undefined
        ? { share: undefined, line: t("billing.daysLeft", { count: format.number(days) }), warn: false }
        : undefined;
  // The plan to pay for: the one now, or for a business that never paid, the plans further down.
  const current = plans.find((plan) => plan.code === billing.plan.code && !isFree(plan));
  const action = free
    ? { label: t("billing.upgrade"), hint: t("billing.freeHint"), plan: undefined }
    : status === "ACTIVE"
      ? { label: t("billing.renew"), hint: t("billing.renewHint"), plan: current }
      : status === "GRACE"
        ? { label: t("billing.payNow"), hint: t("billing.payNowHint"), plan: current }
        : status === "READ_ONLY"
          ? { label: t("billing.reopen"), hint: t("billing.readOnlyHint"), plan: current }
          : undefined;
  return (
    <section
      aria-label={t("billing.yourPlan")}
      className="flex flex-col gap-4 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-5.5 md:pr-7 md:pl-14 md:text-white"
    >
      <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
        <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-1.5">
          <p className="text-[15px] font-semibold text-ledger-text">{t("billing.yourPlan")}</p>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <span className="text-[34px] leading-tight font-bold text-marigold-text md:text-[40px]">
              {planName(billing.plan)}
            </span>
            <span className={cn("rounded-full px-3 py-0.5 text-sm font-bold", pill)}>
              {t(`billing.status.${status}` as "billing.status.ACTIVE")}
            </span>
          </p>
          {dateLine && <p className="text-base text-[#E4EEE9]">{dateLine}</p>}
          {meter && (
            <div className="mt-1 max-w-105">
              {meter.share !== undefined && (
                <div aria-hidden="true" className="h-2 rounded bg-ledger-line">
                  <div
                    className={cn("h-2 rounded", meter.warn ? "bg-marigold" : "bg-[#7FC8A9]")}
                    style={{ width: `${Math.max(4, Math.min(100, meter.share * 100))}%` }}
                  />
                </div>
              )}
              <p className="mt-1 text-sm text-ledger-text">{meter.line}</p>
            </div>
          )}
        </div>
        {canPay && action && (
          <div className="flex flex-col gap-2 max-md:w-full md:items-end">
            {action.plan ? (
              <button
                type="button"
                className={cn(
                  "h-12.5 cursor-pointer rounded-xl bg-marigold-text px-5.5 text-[17px] font-bold text-ledger hover:bg-marigold",
                  ON_DARK_FOCUS,
                )}
                onClick={() => action.plan && onPay(action.plan)}
              >
                {action.label}
              </button>
            ) : (
              <a
                href="#plans"
                className={cn(
                  "flex h-12.5 items-center justify-center rounded-xl bg-marigold-text px-5.5 text-[17px] font-bold text-ledger no-underline hover:bg-marigold hover:text-ledger",
                  ON_DARK_FOCUS,
                )}
              >
                {action.label}
              </a>
            )}
            <span className="text-sm text-ledger-text">{action.hint}</span>
          </div>
        )}
      </div>
      {pending && billing.currentPeriodEnd && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-ledger-outline bg-[#0B4D3A] px-3.5 py-3">
          <p className="text-[15px]">
            {t("billing.pendingLine", {
              date: format.date(billing.currentPeriodEnd),
              plan: planName(pending),
              current: planName(billing.plan),
            })}
          </p>
          {canPay && (
            <button
              type="button"
              disabled={keep.isPending}
              className={cn(
                "h-10 flex-none cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text px-3.5 text-sm font-bold text-white hover:bg-white/10",
                ON_DARK_FOCUS,
              )}
              onClick={() => keep.mutate()}
            >
              {t("billing.keep", { plan: planName(billing.plan) })}
            </button>
          )}
        </div>
      )}
      {(status === "GRACE" || status === "READ_ONLY") && (
        <p
          role="status"
          className={cn(
            "flex items-start gap-2.5 rounded-xl px-3.5 py-3 text-[15px] font-semibold",
            status === "GRACE" ? "bg-warning text-warning-foreground" : "bg-danger-soft text-danger-ink",
          )}
        >
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-5 flex-none" />
          {status === "GRACE"
            ? t("billing.graceAlert", { date: format.date(billing.graceEndsAt ?? billing.currentPeriodEnd ?? "") })
            : t("billing.readOnlyAlert")}
        </p>
      )}
      {!canPay && status !== "SUSPENDED" && <p className="text-[15px] text-ledger-text">{t("billing.ownerPays")}</p>}
    </section>
  );
}

function Usage({ billing }: { billing: Billing }) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  return (
    <section aria-labelledby="usage-title" className={cn(CARD, "p-3.5 md:px-5 md:py-4.5")}>
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h2 id="usage-title" className="text-[21px] font-bold">
          {t("billing.usageTitle")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("billing.usageNote", { plan: planName(billing.plan) })}</p>
      </div>
      <ul className="grid gap-3 md:grid-cols-3">
        {USAGE.map((metric) => {
          const usage = billing.usage[metric];
          if (!usage) {
            return null;
          }
          const { used, limit } = usage;
          const ownerAlone = metric === "staff_seats" && limit === 0;
          const share = limit ? used / limit : 0;
          const near = !!limit && share >= NEAR_LIMIT;
          const note = ownerAlone
            ? t("billing.ownerOnlyNote")
            : limit === null
              ? t("billing.anyAmount")
              : near
                ? used >= limit
                  ? t("billing.full")
                  : t("billing.nearLimit")
                : t(metric === "staff_seats" ? "billing.staffLeft" : "billing.left", {
                    count: format.number(limit - used),
                  });
          return (
            <li
              key={metric}
              className={cn(
                "flex flex-col gap-1.5 rounded-xl border p-3.5",
                near ? "border-[#EBC79A] bg-[#FDF8F1]" : "bg-card",
              )}
            >
              <span className="text-[15px] font-semibold text-[#3E4842]">{t(`billing.usage.${metric}`)}</span>
              {ownerAlone ? (
                <strong className="text-[26px] leading-tight">{t("billing.ownerOnly")}</strong>
              ) : (
                <span>
                  <strong className="text-[26px] leading-tight">{format.number(used)}</strong>{" "}
                  <span className="text-[15px] text-muted-foreground">
                    {limit === null ? t("billing.noLimit") : t("billing.ofLimit", { limit: format.number(limit) })}
                  </span>
                </span>
              )}
              {!ownerAlone && (
                <span aria-hidden="true" className="block h-2 rounded bg-muted">
                  <span
                    className={cn(
                      "block h-2 rounded",
                      limit === null ? "bg-mint-line" : near ? "bg-[#C4820F]" : "bg-primary",
                    )}
                    style={{ width: limit === null ? "100%" : `${Math.max(2, Math.min(100, share * 100))}%` }}
                  />
                </span>
              )}
              <span className={cn("text-[13px]", near ? "font-bold text-warning-foreground" : "text-muted-foreground")}>
                {note}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function PlanFeatures({ plan }: { plan: Plan }) {
  const { t } = useTranslation();
  const format = useFormat();
  const value = (metric: string) => plan.limits[metric] ?? null;
  const variants = value("variants");
  const orders = value("orders_per_month");
  const staff = value("staff_seats");
  const reports = value("report_days");
  const lines: [boolean, string][] = [
    [
      true,
      variants === null
        ? t("billing.limit.variantsAny")
        : t("billing.limit.variants", { count: format.number(variants) }),
    ],
    [
      true,
      orders === null ? t("billing.limit.ordersAny") : t("billing.limit.orders", { count: format.number(orders) }),
    ],
    [
      true,
      staff === null
        ? t("billing.limit.staffAny")
        : staff === 0
          ? t("billing.staffOwner")
          : t("billing.limit.staff", { count: format.number(staff) }),
    ],
    [
      true,
      reports === null
        ? t("billing.limit.reportsAny")
        : reports >= 365
          ? t("billing.reportsYear")
          : t("billing.limit.reports", { count: format.number(reports) }),
    ],
    [plan.exports, plan.exports ? t("billing.exportsAll") : t("billing.limit.noExports")],
  ];
  return (
    <ul className="flex flex-col gap-2 text-[15px]">
      {lines.map(([yes, text]) => (
        <li key={text} className={cn("flex items-start gap-2", !yes && "text-muted-foreground")}>
          {yes ? (
            <Check aria-hidden="true" className="mt-0.5 size-4.5 flex-none text-primary" strokeWidth={2.6} />
          ) : (
            <Minus aria-hidden="true" className="mt-0.5 size-4.5 flex-none text-[#9AA19C]" strokeWidth={2.4} />
          )}
          {text}
        </li>
      ))}
    </ul>
  );
}

type Action =
  | { kind: "pay" | "upgrade" | "downgrade" | "keep" | "free"; label: string; primary: boolean; hint?: string }
  | { kind: "none"; label: string; primary: false; hint?: string };

function Plans({
  billing,
  plans,
  months,
  onMonths,
  onPay,
}: {
  billing: Billing;
  plans: Plan[];
  months: Months;
  onMonths: (months: Months) => void;
  onPay: (plan: Plan, upgrade: boolean) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<{ plan: Plan; free: boolean }>();
  const schedule = useMutation({
    mutationFn: (planCode: string) => call(api.PUT("/api/v1/billing/plan", { body: { planCode } })),
    onSuccess: (updated, planCode) => {
      queryClient.setQueryData(billingQuery.queryKey, updated);
      billingChanged(queryClient);
      toast.success(
        t(
          updated.plan.code === planCode && planCode !== billing.plan.code
            ? "billing.goFreeDone"
            : updated.pendingPlanCode
              ? "billing.downgradeSet"
              : "billing.downgradeCancelled",
        ),
      );
    },
    onSettled: () => setConfirm(undefined),
  });
  const status = billing.status;
  const paidNow = status === "ACTIVE" && !isFree(billing.plan);
  const onFree = status === "ACTIVE" && isFree(billing.plan);
  const until = billing.currentPeriodEnd ? format.date(billing.currentPeriodEnd) : "";

  function action(plan: Plan): Action {
    const current = plan.code === billing.plan.code;
    if (paidNow) {
      if (billing.pendingPlanCode === plan.code) {
        return { kind: "keep", label: t("billing.keepCurrent"), primary: false };
      }
      if (current) {
        return { kind: "pay", label: t("billing.renew"), primary: false };
      }
      if (plan.priceMonthly > billing.plan.priceMonthly) {
        return { kind: "upgrade", label: t("billing.upgrade"), primary: true, hint: t("billing.upgradeHintCard") };
      }
      return {
        kind: "downgrade",
        label: t("billing.downgrade"),
        primary: false,
        hint: t("billing.downgradeHintCard", { date: until, plan: planName(plan), current: planName(billing.plan) }),
      };
    }
    if (isFree(plan)) {
      return onFree
        ? { kind: "none", label: t("billing.currentPlan"), primary: false }
        : { kind: "free", label: t("billing.goFree"), primary: false, hint: t("billing.goFreeHint") };
    }
    return { kind: "pay", label: t("billing.choose"), primary: true };
  }

  function run(plan: Plan, chosen: Action) {
    if (chosen.kind === "pay" || chosen.kind === "upgrade") {
      onPay(plan, chosen.kind === "upgrade");
    } else if (chosen.kind === "downgrade" || chosen.kind === "free") {
      setConfirm({ plan, free: chosen.kind === "free" });
    } else if (chosen.kind === "keep") {
      schedule.mutate(billing.plan.code);
    }
  }

  return (
    <section id="plans" aria-labelledby="plans-title" className={cn(CARD, "scroll-mt-4 p-3.5 md:px-5 md:py-4.5")}>
      <div className="mb-3.5 flex flex-wrap items-center justify-between gap-x-4 gap-y-2.5">
        <div>
          <h2 id="plans-title" className="text-[21px] font-bold">
            {t("billing.choosePlan")}
          </h2>
          <p className="text-sm text-muted-foreground">{t("billing.plansHint")}</p>
        </div>
        {/* biome-ignore lint/a11y/useSemanticElements: three buttons that belong together, with no form around them. */}
        <div role="group" aria-label={t("billing.periodLabel")} className="flex gap-1 rounded-xl bg-muted p-1">
          {MONTH_CHOICES.map((choice) => (
            <button
              key={choice}
              type="button"
              aria-pressed={months === choice}
              className={cn(
                "flex min-h-11 cursor-pointer items-center gap-1.5 rounded-[9px] px-3 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-1 focus-visible:outline-ring",
                months === choice ? "bg-card shadow-sm" : "hover:bg-card/60",
              )}
              onClick={() => onMonths(choice)}
            >
              {t(`billing.monthsShort.${choice}`)}
              {choice === 12 && (
                <span className="rounded-full bg-primary px-1.75 text-xs font-bold text-primary-foreground">
                  {t("billing.twoFree")}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>
      <div className="grid gap-3.5 lg:grid-cols-3">
        {plans.map((plan) => {
          const chosen = action(plan);
          const current = plan.code === billing.plan.code;
          const free = isFree(plan);
          return (
            <article
              key={plan.code}
              aria-labelledby={`plan-${plan.code}`}
              className={cn(
                "flex flex-col gap-3.5 rounded-2xl p-4.5",
                current ? "border-2 border-primary bg-[#FBFDFC]" : "border bg-card",
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <h3 id={`plan-${plan.code}`} className="text-[22px] font-bold">
                  {planName(plan)}
                </h3>
                {current && (
                  <span className="rounded-full bg-accent px-2.5 py-0.5 text-[13px] font-bold text-accent-foreground">
                    {t("billing.yourPlan")}
                  </span>
                )}
              </div>
              <p className="text-sm text-muted-foreground">
                {t(`billing.blurb.${plan.code}` as "billing.blurb.FREE", { defaultValue: "" })}
              </p>
              <div>
                <span className="text-[34px] leading-none font-bold">
                  {free ? t("billing.freePrice") : format.taka(plan.priceMonthly)}
                </span>
                {!free && <span className="text-[15px] text-muted-foreground">{t("billing.perMonthShort")}</span>}
                <p className="mt-1 min-h-5.5 text-sm font-semibold text-primary">
                  {free
                    ? t("billing.freeTotal")
                    : months === 1
                      ? ""
                      : `${t("billing.periodTotal", {
                          months: format.number(months),
                          amount: format.taka(monthsPrice(plan.priceMonthly, months)),
                        })}${months === 12 ? ` · ${t("billing.saving", { amount: format.taka(plan.priceMonthly * 2) })}` : ""}`}
                </p>
              </div>
              <PlanFeatures plan={plan} />
              <button
                type="button"
                disabled={chosen.kind === "none" || schedule.isPending}
                className={cn(
                  "mt-auto h-12 cursor-pointer rounded-xl border-[1.5px] text-base font-bold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-default",
                  chosen.kind === "none"
                    ? "border-muted bg-muted text-muted-foreground"
                    : chosen.primary
                      ? "border-primary bg-primary text-primary-foreground hover:bg-[#0a5c44]"
                      : "border-primary bg-card text-primary hover:bg-accent",
                )}
                onClick={() => run(plan, chosen)}
              >
                {chosen.label}
              </button>
              {chosen.hint && <p className="-mt-1.5 text-center text-[13px] text-muted-foreground">{chosen.hint}</p>}
            </article>
          );
        })}
      </div>
      {confirm && (
        <ConfirmDialog
          title={
            confirm.free ? t("billing.goFreeTitle") : t("billing.downgradeTitle", { plan: planName(confirm.plan) })
          }
          description={
            confirm.free && !paidNow
              ? t("billing.goFreeDescription")
              : t("billing.downgradeDescription", { date: until })
          }
          confirm={confirm.free && !paidNow ? t("billing.goFree") : t("billing.downgrade")}
          pending={schedule.isPending}
          onConfirm={() => schedule.mutate(confirm.plan.code)}
          onClose={() => setConfirm(undefined)}
        />
      )}
    </section>
  );
}

function Invoices() {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const { data: plans } = useQuery(plansQuery);
  const [paging, setPaging] = useState<Paging>({});
  const top = useRef<HTMLElement>(null);
  const { data: invoices } = useQuery(invoicesQuery(paging));
  const items = invoices?.items ?? [];
  const nameOf = (code: string) => {
    const plan = plans?.find((one) => one.code === code);
    return plan ? planName(plan) : code;
  };
  const what = (invoice: Invoice) =>
    t(`billing.what.${invoice.kind}` as "billing.what.SUBSCRIPTION", {
      plan: nameOf(invoice.planCode),
      months: format.number(invoice.months),
    });
  const badge = (invoice: Invoice) =>
    invoice.status === "PAID"
      ? "bg-accent text-accent-foreground"
      : invoice.status === "OPEN"
        ? "bg-warning text-warning-foreground"
        : "bg-muted text-muted-foreground";
  const statusText = (invoice: Invoice) => t(`billing.invoiceStatus.${invoice.status}` as "billing.invoiceStatus.PAID");
  return (
    <section ref={top} aria-labelledby="invoices-title" className={cn(CARD, "scroll-mt-4 overflow-hidden")}>
      <div className="flex items-baseline justify-between gap-3 px-3.5 pt-3.5 pb-2.5 md:px-5 md:pt-4 md:pb-3">
        <h2 id="invoices-title" className="text-[21px] font-bold">
          {t("billing.invoices")}
        </h2>
        {!!invoices?.totalItems && (
          <span className="text-sm text-muted-foreground">
            {t("billing.invoiceCount", { count: format.number(invoices.totalItems) })}
          </span>
        )}
      </div>
      {invoices && items.length === 0 && (
        <p className="border-t px-4 pt-6 pb-8 text-center text-muted-foreground">{t("billing.noInvoices")}</p>
      )}
      {items.length > 0 && (
        <>
          <table className="w-full border-collapse text-[15px] max-md:hidden">
            <thead>
              <tr className="bg-muted text-left text-sm text-muted-foreground">
                <th scope="col" className="py-2 pr-2.5 pl-5 font-semibold">
                  {t("billing.column.invoice")}
                </th>
                <th scope="col" className="px-2.5 py-2 font-semibold">
                  {t("billing.column.what")}
                </th>
                <th scope="col" className="px-2.5 py-2 font-semibold">
                  {t("billing.column.how")}
                </th>
                <th scope="col" className="px-2.5 py-2 text-right font-semibold">
                  {t("billing.column.total")}
                </th>
                <th scope="col" className="py-2 pr-5 pl-2.5 font-semibold">
                  {t("billing.column.status")}
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((invoice) => (
                <tr key={invoice.id} className="border-t">
                  <th scope="row" className="py-2.75 pr-2.5 pl-5 text-left font-semibold">
                    <Link to="/billing/invoices/$invoiceId" params={{ invoiceId: invoice.id }}>
                      {invoice.number}
                    </Link>
                    <span className="block text-[13px] font-normal text-muted-foreground">
                      {format.date(invoice.createdAt)}
                    </span>
                  </th>
                  <td className="px-2.5 py-2.75">
                    {what(invoice)}
                    {invoice.periodStart && invoice.periodEnd && (
                      <span className="block text-[13px] text-muted-foreground">
                        {t("billing.periodRange", {
                          from: format.dayMonth(invoice.periodStart),
                          to: format.date(invoice.periodEnd),
                        })}
                      </span>
                    )}
                  </td>
                  <td className="px-2.5 py-2.75">{t(`billing.how.${invoice.payBy}` as "billing.how.SSLCOMMERZ")}</td>
                  <td className="px-2.5 py-2.75 text-right font-bold whitespace-nowrap">
                    {format.taka(invoice.amount)}
                  </td>
                  <td className="py-2.75 pr-5 pl-2.5">
                    <span
                      className={cn("rounded-full px-2.5 py-0.5 text-sm font-bold whitespace-nowrap", badge(invoice))}
                    >
                      {statusText(invoice)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className="md:hidden">
            {items.map((invoice) => (
              <li key={invoice.id} className="border-t">
                <Link
                  to="/billing/invoices/$invoiceId"
                  params={{ invoiceId: invoice.id }}
                  className="flex items-center justify-between gap-3 px-3.5 py-3 text-foreground no-underline hover:text-foreground"
                >
                  <span className="min-w-0">
                    <span className="block font-bold">{invoice.number}</span>
                    <span className="block text-[13px] text-muted-foreground">
                      {what(invoice)} · {format.date(invoice.createdAt)}
                    </span>
                  </span>
                  <span className="flex-none text-right">
                    <span className="block font-bold">{format.taka(invoice.amount)}</span>
                    <span className={cn("mt-0.5 inline-block rounded-full px-2 text-[13px] font-bold", badge(invoice))}>
                      {statusText(invoice)}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
          <div className="border-t px-3.5 pt-3 pb-3.5 md:px-5">
            <Pagination result={invoices} onChange={setPaging} scrollTo={top} />
          </div>
        </>
      )}
    </section>
  );
}
