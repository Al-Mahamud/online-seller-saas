import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { type ReactNode, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorMessages } from "@/api/query";
import { LedgerDialog } from "@/components/layout/LedgerDialog";
import { useLooseT } from "@/i18n/pages";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Billing, billingChanged, type Plan, quoteQuery, usePlanName } from "./api";
import { MONTH_CHOICES, type Months, monthsPrice, periodEnd } from "./plans";

/** bKash and Nagad open on SSLCommerz's page for that wallet; "other" lets the seller choose there. */
const WALLETS = ["bkash", "nagad"] as const;
type Wallet = (typeof WALLETS)[number];
type Way = Wallet | "other" | "bank";
const OTHER_WAYS = ["rocket", "upay", "card", "netBanking"] as const;
/** A letter on the wallet's colour, until the wallets' own logo files are in (docs/plans-and-payment.md). */
const MARK: Record<Wallet, string> = { bkash: "bg-[#D6125F]", nagad: "bg-[#C2410C]" };

const isWallet = (way: Way): way is Wallet => way === "bkash" || way === "nagad";

/** A card that is a radio button, with what it means under its name. */
function Option({
  name,
  checked,
  onChange,
  children,
  badge,
  badgeTone = "primary",
}: {
  name: string;
  checked: boolean;
  onChange: () => void;
  children: ReactNode;
  badge?: string;
  badgeTone?: "primary" | "danger";
}) {
  return (
    <label
      className={cn(
        "relative flex cursor-pointer flex-col gap-0.5 rounded-2xl py-3 pr-3 pl-10 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
        checked ? "border-2 border-primary bg-[#F3F9F6]" : "border-[1.5px] border-input bg-card hover:bg-muted",
      )}
    >
      <input
        type="radio"
        name={name}
        checked={checked}
        onChange={onChange}
        className="absolute top-3.75 left-3 size-4.5 accent-primary"
      />
      {children}
      {badge && (
        <span
          className={cn(
            "absolute -top-2.5 right-2.5 rounded-full px-2 text-xs font-bold",
            badgeTone === "danger" ? "bg-danger-ink text-white" : "bg-primary text-primary-foreground",
          )}
        >
          {badge}
        </span>
      )}
    </label>
  );
}

/**
 * Paying for a plan: some months of it, or (`upgrade`) the difference for the rest of the running
 * period. The total is the server's own quote, so it is what will be charged.
 */
export function PayDialog({
  plan,
  upgrade,
  initialMonths,
  billing,
  onClose,
}: {
  plan: Plan;
  upgrade: boolean;
  initialMonths: Months;
  billing: Billing | undefined;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const id = useId();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [months, setMonths] = useState<Months>(initialMonths);
  const loose = useLooseT();
  const [way, setWay] = useState<Way>("bkash");
  // A wallet the gateway just refused: marked on its tile until the dialog closes.
  const [off, setOff] = useState<Wallet>();
  const payBy = way === "bank" ? "BANK_TRANSFER" : "SSLCOMMERZ";
  const wallet = isWallet(way) ? (way.toUpperCase() as "BKASH" | "NAGAD") : undefined;
  const quote = useQuery(quoteQuery(plan.code, upgrade ? undefined : months));
  const pay = useMutation({
    mutationFn: () =>
      call(
        upgrade
          ? api.POST("/api/v1/billing/upgrade", { body: { planCode: plan.code, payBy, wallet } })
          : api.POST("/api/v1/billing/checkout", { body: { planCode: plan.code, months, payBy, wallet } }),
      ),
    meta: { showsOwnErrors: true },
    onError: (error) => {
      if (isWallet(way) && error instanceof ApiError && error.code === "billing.gateway_error") {
        setOff(way);
      }
    },
    onSuccess: async (checkout) => {
      billingChanged(queryClient);
      if (checkout.gatewayUrl) {
        // Off to the gateway's page, which comes back to /billing/return.
        window.location.assign(checkout.gatewayUrl);
      } else {
        await navigate({ to: "/billing/invoices/$invoiceId", params: { invoiceId: checkout.invoiceId } });
      }
    },
  });
  const problem = pay.error ?? quote.error;
  // The gateway refusing a wallet says which, and what else will do.
  const problemText =
    pay.error && off && pay.error instanceof ApiError && pay.error.code === "billing.gateway_error"
      ? loose(`pay.off.${off}`)
      : problem
        ? errorMessages(problem)
        : undefined;
  // A renewal of the plan running now adds to its end; anything else starts today.
  const renewing =
    !upgrade &&
    billing?.status === "ACTIVE" &&
    billing.plan.code === plan.code &&
    !!billing.currentPeriodEnd &&
    new Date(billing.currentPeriodEnd).getTime() > Date.now();
  const start = renewing && billing?.currentPeriodEnd ? new Date(billing.currentPeriodEnd) : new Date();
  const end = upgrade ? (billing?.currentPeriodEnd ?? undefined) : periodEnd(start, months).toISOString();
  const period = end
    ? t("billing.periodRange", {
        from: renewing ? format.date(start) : t("billing.today"),
        to: format.date(end),
      })
    : "";
  const total = quote.data ? format.taka(quote.data.amount) : "…";
  return (
    <LedgerDialog
      title={t(upgrade ? "billing.upgradeTitle" : "billing.payTitle", { plan: planName(plan) })}
      description={
        upgrade && billing
          ? t("billing.upgradeFrom", { from: planName(billing.plan), to: planName(plan) })
          : t("billing.monthlyPrice", { amount: format.taka(plan.priceMonthly) })
      }
      figures={[
        { label: t("billing.payFigure"), value: total },
        ...(period ? [{ label: t("billing.periodFigure"), value: period }] : []),
      ]}
      problem={problemText}
      summary={
        isWallet(way) ? loose(`pay.next.${way}`) : way === "other" ? t("billing.onlineNext") : t("billing.bankNext")
      }
      action={
        way === "bank"
          ? t("billing.getInstructions")
          : !quote.data
            ? t("billing.payAmount", { amount: total })
            : isWallet(way)
              ? loose(`pay.payWith.${way}`, { amount: total })
              : t("billing.payAmount", { amount: total })
      }
      pending={pay.isPending || !quote.data}
      onSubmit={() => pay.mutate()}
      onClose={onClose}
    >
      {upgrade ? (
        <div className="rounded-2xl border border-mint-line bg-[#F3F9F6] p-3.5">
          <p className="mb-1.5 font-bold">{t("billing.upgradeOnly")}</p>
          <p className="text-[15px] text-[#3E4842]">
            {t("billing.upgradeExplain", {
              plan: planName(plan),
              date: billing?.currentPeriodEnd ? format.date(billing.currentPeriodEnd) : "",
            })}
          </p>
        </div>
      ) : (
        <fieldset className="min-w-0">
          <legend className="mb-2 font-bold">{t("billing.forHowLong")}</legend>
          <div className="grid gap-2 md:grid-cols-3">
            {MONTH_CHOICES.map((choice) => {
              const price = monthsPrice(plan.priceMonthly, choice);
              return (
                <Option
                  key={choice}
                  name={`${id}-months`}
                  checked={months === choice}
                  onChange={() => setMonths(choice)}
                  badge={choice === 12 ? t("billing.twoFree") : undefined}
                >
                  <span className="font-bold">{t(`billing.monthsShort.${choice}`)}</span>
                  <span className="text-lg font-bold">{format.taka(price)}</span>
                  <span className="text-[13px] text-muted-foreground">
                    {choice === 1
                      ? t("billing.everyMonth")
                      : t("billing.eachMonth", { amount: format.taka(Math.round(price / choice / 100) * 100) })}
                  </span>
                </Option>
              );
            })}
          </div>
        </fieldset>
      )}

      <fieldset className="min-w-0">
        <legend className="mb-2 font-bold">{t("billing.payHow")}</legend>
        <div className="flex flex-col gap-2">
          <div className="grid grid-cols-2 gap-2">
            {WALLETS.map((one) => (
              <Option
                key={one}
                name={`${id}-pay-by`}
                checked={way === one}
                onChange={() => setWay(one)}
                badge={off === one ? loose("pay.offNow") : undefined}
                badgeTone="danger"
              >
                <span className="flex items-center gap-2.5">
                  <span
                    aria-hidden="true"
                    className={cn(
                      "flex size-10 flex-none items-center justify-center rounded-xl text-[19px] leading-none font-bold text-white",
                      MARK[one],
                    )}
                  >
                    {loose(`pay.letter.${one}`)}
                  </span>
                  <span className="text-xl font-bold">{loose(`pay.wallet.${one}`)}</span>
                </span>
                <span className="mt-1.5 text-sm text-[#4A544E]">{loose(`pay.hint.${one}`)}</span>
              </Option>
            ))}
          </div>
          <Option name={`${id}-pay-by`} checked={way === "other"} onChange={() => setWay("other")}>
            <span className="font-bold">{loose("pay.other")}</span>
            <span className="text-sm text-muted-foreground">{loose("pay.otherHint")}</span>
            <span className="mt-1.5 flex flex-wrap gap-1.5">
              {OTHER_WAYS.map((other) => (
                <span key={other} className="rounded-lg border bg-card px-2.5 text-[13px] font-bold text-[#3E4842]">
                  {loose(`pay.otherWays.${other}`)}
                </span>
              ))}
            </span>
          </Option>
          <Option name={`${id}-pay-by`} checked={way === "bank"} onChange={() => setWay("bank")}>
            <span className="font-bold">{t("billing.payBy.BANK_TRANSFER")}</span>
            <span className="text-sm text-muted-foreground">{t("billing.bankHint")}</span>
          </Option>
        </div>
      </fieldset>

      {quote.data && (
        <div role="status">
          <dl className="flex flex-col gap-1.5 rounded-2xl bg-background p-3.5 text-[15px]">
            {!upgrade && (
              <div className="flex justify-between gap-3">
                <dt>{t("billing.lineMonths", { plan: planName(plan), months: format.number(months) })}</dt>
                <dd className="whitespace-nowrap">{format.taka(plan.priceMonthly * months)}</dd>
              </div>
            )}
            {!upgrade && months === 12 && (
              <div className="flex justify-between gap-3 text-primary">
                <dt>{t("billing.twoFree")}</dt>
                <dd className="whitespace-nowrap">−{format.taka(plan.priceMonthly * 2)}</dd>
              </div>
            )}
            {quote.data.vatAmount > 0 && (
              <div className="flex justify-between gap-3">
                <dt>{t("billing.vat")}</dt>
                <dd className="whitespace-nowrap">{format.taka(quote.data.vatAmount)}</dd>
              </div>
            )}
            <div className="flex justify-between gap-3 border-t pt-2 text-lg font-bold">
              <dt>{t("billing.total")}</dt>
              <dd className="whitespace-nowrap">{total}</dd>
            </div>
          </dl>
          <p className="mt-1.5 px-1 text-[13px] text-muted-foreground">{t("billing.receiptNote")}</p>
        </div>
      )}
    </LedgerDialog>
  );
}
