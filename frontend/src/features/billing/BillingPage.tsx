import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
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

const PayDialog = lazy(() => import("./PayDialog").then((module) => ({ default: module.PayDialog })));

const USAGE = ["variants", "orders_per_month", "staff_seats"] as const;
const NEAR_LIMIT = 0.9;

export function BillingPage() {
  const { t } = useTranslation();
  const business = useBusiness();
  const { data: billing } = useQuery(billingQuery);
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <div>
        <BackToMore />
        <PageHeader title={t("billing.title")} />
      </div>
      {billing && <CurrentPlan billing={billing} />}
      {billing && hasRole(business, "OWNER") && billing.status !== "SUSPENDED" && <Plans billing={billing} />}
      {billing && !hasRole(business, "OWNER") && <p className="text-muted-foreground">{t("billing.ownerPays")}</p>}
      <Invoices />
    </div>
  );
}

function CurrentPlan({ billing }: { billing: Billing }) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const { data: plans } = useQuery(plansQuery);
  const pending = plans?.find((plan) => plan.code === billing.pendingPlanCode);
  const until =
    billing.status === "TRIALING"
      ? billing.trialEndsAt && t("billing.trialUntil", { date: format.date(billing.trialEndsAt) })
      : billing.status === "GRACE"
        ? billing.graceEndsAt && t("billing.graceUntil", { date: format.date(billing.graceEndsAt) })
        : billing.status === "ACTIVE"
          ? billing.currentPeriodEnd && t("billing.paidUntil", { date: format.date(billing.currentPeriodEnd) })
          : null;
  return (
    <Section title={t("billing.yourPlan")}>
      <div>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="text-xl font-bold">{planName(billing.plan)}</span>
          <span className="rounded-full bg-accent px-2.5 text-[13px] font-semibold text-accent-foreground">
            {t(`billing.status.${billing.status}` as "billing.status.ACTIVE")}
          </span>
        </p>
        {until && <p className="text-muted-foreground">{until}</p>}
        {pending && billing.currentPeriodEnd && (
          <p className="mt-1">
            {t("billing.changesTo", { plan: planName(pending), date: format.date(billing.currentPeriodEnd) })}
          </p>
        )}
      </div>
      <ul className="flex flex-col gap-3">
        {USAGE.map((metric) => {
          const usage = billing.usage[metric];
          if (!usage) {
            return null;
          }
          const share = usage.limit ? Math.min(1, usage.used / usage.limit) : 0;
          return (
            <li key={metric}>
              <p className="flex justify-between gap-3">
                <span>{t(`planLimit.metric.${metric}`)}</span>
                <span className="font-semibold">
                  {usage.limit === null
                    ? t("billing.usedOfNoLimit", { used: format.number(usage.used) })
                    : t("billing.usedOf", { used: format.number(usage.used), limit: format.number(usage.limit) })}
                </span>
              </p>
              {usage.limit !== null && (
                <span aria-hidden="true" className="mt-1 block h-2 overflow-hidden rounded bg-muted">
                  <span
                    className={cn("block h-2 rounded", share >= NEAR_LIMIT ? "bg-warning-foreground" : "bg-primary")}
                    style={{ width: `${share * 100}%` }}
                  />
                </span>
              )}
            </li>
          );
        })}
      </ul>
    </Section>
  );
}

type Choice = { plan: Plan; action: "pay" | "upgrade" | "downgrade" };

function Plans({ billing }: { billing: Billing }) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const queryClient = useQueryClient();
  const { data: plans } = useQuery(plansQuery);
  const [choice, setChoice] = useState<Choice>();
  const active = billing.status === "ACTIVE";
  const schedule = useMutation({
    mutationFn: (planCode: string) => call(api.PUT("/api/v1/billing/plan", { body: { planCode } })),
    onSuccess: (updated) => {
      queryClient.setQueryData(billingQuery.queryKey, updated);
      billingChanged(queryClient);
      toast.success(t(updated.pendingPlanCode ? "billing.downgradeSet" : "billing.downgradeCancelled"));
    },
    onSettled: () => setChoice(undefined),
  });

  function button(plan: Plan) {
    const current = plan.code === billing.plan.code;
    if (active && billing.pendingPlanCode === plan.code) {
      return (
        <Button variant="outline" disabled={schedule.isPending} onClick={() => schedule.mutate(billing.plan.code)}>
          {t("billing.keepCurrent")}
        </Button>
      );
    }
    if (active && !current && plan.priceMonthly < billing.plan.priceMonthly) {
      return (
        <Button variant="outline" onClick={() => setChoice({ plan, action: "downgrade" })}>
          {t("billing.downgrade")}
        </Button>
      );
    }
    if (active && !current) {
      return <Button onClick={() => setChoice({ plan, action: "upgrade" })}>{t("billing.upgrade")}</Button>;
    }
    return (
      <Button variant={current ? "outline" : "default"} onClick={() => setChoice({ plan, action: "pay" })}>
        {t(current ? "billing.renew" : "billing.choose")}
      </Button>
    );
  }

  return (
    <Section title={t("billing.choosePlan")}>
      <ul className="grid gap-3 sm:grid-cols-2">
        {plans?.map((plan) => (
          <li key={plan.code} className="flex flex-col gap-2 rounded-lg border p-3.5">
            <div>
              <h3 className="text-lg font-semibold">{planName(plan)}</h3>
              <p>
                <span className="text-xl font-bold">{format.taka(plan.priceMonthly)}</span> {t("billing.perMonth")}
              </p>
            </div>
            <PlanLimits plan={plan} />
            <div className="mt-auto">{button(plan)}</div>
          </li>
        ))}
      </ul>
      {choice && choice.action !== "downgrade" && (
        <Suspense fallback={null}>
          <PayDialog plan={choice.plan} upgrade={choice.action === "upgrade"} onClose={() => setChoice(undefined)} />
        </Suspense>
      )}
      {choice?.action === "downgrade" && billing.currentPeriodEnd && (
        <ConfirmDialog
          title={t("billing.downgradeTitle", { plan: planName(choice.plan) })}
          description={t("billing.downgradeDescription", { date: format.date(billing.currentPeriodEnd) })}
          confirm={t("billing.downgrade")}
          pending={schedule.isPending}
          onConfirm={() => schedule.mutate(choice.plan.code)}
          onClose={() => setChoice(undefined)}
        />
      )}
    </Section>
  );
}

function PlanLimits({ plan }: { plan: Plan }) {
  const { t } = useTranslation();
  const format = useFormat();
  const limit = (metric: string, some: "variants" | "orders" | "staff" | "reports", unlimited: boolean) => {
    const value = plan.limits[metric];
    if (value === null || value === undefined) {
      return unlimited ? t(`billing.limit.${some}Any`) : null;
    }
    return t(`billing.limit.${some}`, { count: format.number(value) });
  };
  const lines = [
    limit("variants", "variants", true),
    limit("orders_per_month", "orders", true),
    limit("staff_seats", "staff", true),
    limit("report_days", "reports", true),
    t(plan.exports ? "billing.limit.exports" : "billing.limit.noExports"),
  ];
  return (
    <ul className="text-sm text-muted-foreground">
      {lines.map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  );
}

function Invoices() {
  const { t } = useTranslation();
  const format = useFormat();
  const { data: invoices } = useQuery(invoicesQuery);
  return (
    <Section title={t("billing.invoices")}>
      {invoices?.length === 0 && <p className="text-muted-foreground">{t("billing.noInvoices")}</p>}
      <ul className="flex flex-col">
        {invoices?.map((invoice: Invoice) => (
          <li key={invoice.id} className="border-t first:border-t-0">
            <Link
              to="/billing/invoices/$invoiceId"
              params={{ invoiceId: invoice.id }}
              className="flex items-center justify-between gap-3 py-2 text-foreground no-underline"
            >
              <span className="min-w-0">
                <span className="block font-medium">{invoice.number}</span>
                <span className="block text-sm text-muted-foreground">{format.date(invoice.createdAt)}</span>
              </span>
              <span className="flex-none text-right">
                <span className="block font-semibold">{format.taka(invoice.amount)}</span>
                <span className="block text-sm text-muted-foreground">
                  {t(`billing.invoiceStatus.${invoice.status}` as "billing.invoiceStatus.PAID")}
                </span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </Section>
  );
}
