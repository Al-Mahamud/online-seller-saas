import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronLeft, Mail, Phone } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { AreaField, Field } from "@/components/form/Field";
import { addDays, dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import { cn } from "@/lib/utils";
import { AdminDialog } from "./AdminDialog";
import { AdminCard, AdminHero, PlanBadge, StateLabel, usePlanNames } from "./AdminParts";
import { AuditEntries } from "./AuditPage";
import { type AdminBusiness, businessQuery, plansQuery } from "./api";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/businesses/$businessId");

type DialogName = "suspend" | "trial" | "plan";

/** One of the admin's changes: the answer updates the page, and a refusal stays in the dialog. */
function useAdminChange<V>(send: (variables: V) => Promise<AdminBusiness>, doneText: string, onDone?: () => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      queryClient.setQueryData(businessQuery(saved.business.id).queryKey, saved);
      void queryClient.invalidateQueries({ queryKey: ["admin"] });
      toast.success(doneText);
      onDone?.();
    },
  });
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="rounded-xl bg-muted/70 px-3.5 py-3">
      <dt className="text-[13px] font-semibold text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 text-[17px] font-bold break-words">{children}</dd>
    </div>
  );
}

export function BusinessPage() {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const format = useFormat();
  const planName = usePlanNames();
  const { businessId } = route.useParams();
  const { data } = useQuery(businessQuery(businessId));
  const { data: plans } = useQuery(plansQuery);
  const [dialog, setDialog] = useState<DialogName>();
  const close = () => setDialog(undefined);
  const lift = useAdminChange(
    () => call(api.POST("/api/v1/admin/businesses/{id}/unsuspend", { params: { path: { id: businessId } } })),
    text.suspend.lifted,
  );
  const back = (
    <Link
      to="/admin/businesses"
      className="-ml-1 flex min-h-9 w-fit items-center gap-1 text-[15px] font-semibold text-marigold-text no-underline"
    >
      <ChevronLeft aria-hidden="true" className="size-4.5" />
      {text.nav.businesses}
    </Link>
  );
  if (!data) {
    return <AdminHero title="" above={back} />;
  }
  const { business, billing, adminActions } = data;
  const suspended = billing.status === "SUSPENDED";
  const plan = plans?.find((candidate) => candidate.code === billing.planCode);
  const owner = business.owner;
  return (
    <div className="flex flex-col gap-4">
      <AdminHero
        above={back}
        title={business.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <PlanBadge code={billing.planCode} className="bg-marigold-text text-ledger" />
            <StateLabel status={billing.status} onDark />
            <span>{text.business.opened(format.date(business.createdAt))}</span>
            <span>{text.business.members(format.number(business.members))}</span>
          </span>
        }
        aside={
          <div className="flex flex-wrap gap-2">
            {suspended ? (
              <button
                type="button"
                disabled={lift.isPending}
                onClick={() => lift.mutate(undefined)}
                className="min-h-11 cursor-pointer rounded-xl bg-marigold-text px-4 font-bold text-ledger hover:bg-marigold disabled:opacity-60"
              >
                {text.suspend.lift}
              </button>
            ) : (
              <>
                {/* Setting a plan makes a business active, so it isn't offered over a suspension. */}
                <button
                  type="button"
                  onClick={() => setDialog("plan")}
                  className="min-h-11 cursor-pointer rounded-xl bg-marigold-text px-4 font-bold text-ledger hover:bg-marigold"
                >
                  {text.setPlan.action}
                </button>
                {billing.status === "TRIALING" && (
                  <button
                    type="button"
                    onClick={() => setDialog("trial")}
                    className="min-h-11 cursor-pointer rounded-xl border-[1.5px] border-ledger-outline px-4 font-bold text-white hover:bg-white/8"
                  >
                    {text.trial.action}
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => setDialog("suspend")}
                  className="min-h-11 cursor-pointer rounded-xl border-[1.5px] border-[#f2a99e] px-4 font-bold text-[#ffd9d3] hover:bg-white/8"
                >
                  {text.suspend.action}
                </button>
              </>
            )}
          </div>
        }
      />

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-4">
          <AdminCard title={text.business.planAndUsage}>
            <dl className="grid gap-2.5 sm:grid-cols-3">
              <Fact label={text.business.plan}>
                {planName(billing.planCode)}
                {plan && plan.priceMonthly > 0 && ` · ${text.business.perMonth(format.taka(plan.priceMonthly))}`}
              </Fact>
              {billing.trialEndsAt ? (
                <Fact label={text.business.trialEnds}>{format.date(billing.trialEndsAt)}</Fact>
              ) : (
                <Fact label={text.business.periodEnds}>
                  {billing.currentPeriodEnd ? format.date(billing.currentPeriodEnd) : text.business.noEnd}
                </Fact>
              )}
              <Fact label={text.business.next}>
                {billing.pendingPlanCode ? planName(billing.pendingPlanCode) : text.business.same}
              </Fact>
            </dl>
            <ul className="flex flex-col gap-3">
              {Object.entries(billing.usage).map(([metric, usage]) => (
                <li key={metric} className="flex flex-col gap-1.5">
                  <span className="flex justify-between gap-3 text-[15px]">
                    <span>{text.plans.limit[metric] ?? metric}</span>
                    <strong>
                      {format.number(usage.used)}
                      {usage.limit === null ? (
                        <span className="font-normal text-muted-foreground"> · {text.business.noLimit}</span>
                      ) : (
                        ` / ${format.number(usage.limit)}`
                      )}
                    </strong>
                  </span>
                  {usage.limit !== null && usage.limit > 0 && (
                    <span aria-hidden="true" className="flex h-2 overflow-hidden rounded-full bg-muted">
                      <span
                        className={cn(
                          "rounded-full",
                          usage.used >= usage.limit
                            ? "bg-destructive"
                            : usage.used / usage.limit >= 0.8
                              ? "bg-warning"
                              : "bg-primary",
                        )}
                        style={{ width: `${Math.min(100, (usage.used / usage.limit) * 100)}%` }}
                      />
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </AdminCard>

          <AdminCard title={text.business.invoices}>
            {billing.invoices.length === 0 ? (
              <p className="text-muted-foreground">{text.business.noInvoices}</p>
            ) : (
              <section
                // Wider than a phone: it scrolls sideways, so the keyboard must reach it.
                // biome-ignore lint/a11y/noNoninteractiveTabindex: a scrolling region needs focus to scroll by keyboard.
                tabIndex={0}
                aria-label={text.business.invoices}
                className="-mx-4 overflow-x-auto focus-visible:outline-2 focus-visible:outline-ring md:-mx-5"
              >
                <table className="w-full min-w-140 border-collapse text-[15px]">
                  <thead>
                    <tr className="text-left text-sm text-muted-foreground">
                      <th scope="col" className="px-4 py-2 font-semibold md:px-5">
                        {text.business.number}
                      </th>
                      <th scope="col" className="px-2.5 py-2 font-semibold">
                        {text.business.what}
                      </th>
                      <th scope="col" className="px-2.5 py-2 font-semibold">
                        {text.business.how}
                      </th>
                      <th scope="col" className="px-2.5 py-2 font-semibold">
                        {text.business.status}
                      </th>
                      <th scope="col" className="py-2 pr-4 pl-2.5 text-right font-semibold md:pr-5">
                        {text.business.amount}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {billing.invoices.map((invoice) => (
                      <tr key={invoice.id} className="border-t border-muted">
                        <td className="px-4 py-2.5 md:px-5">
                          <span className="block font-bold">{invoice.number}</span>
                          <span className="text-[13px] text-muted-foreground">{format.date(invoice.createdAt)}</span>
                        </td>
                        <td className="px-2.5 py-2.5">
                          {invoice.kind === "UPGRADE"
                            ? text.invoice.upgrade(planName(invoice.planCode))
                            : text.invoice.plan(planName(invoice.planCode), format.number(invoice.months))}
                        </td>
                        <td className="px-2.5 py-2.5">{text.invoice.payBy[invoice.payBy] ?? invoice.payBy}</td>
                        <td className="px-2.5 py-2.5">
                          <span
                            className={cn(
                              "rounded-full px-2.5 py-0.5 text-[13px] font-bold",
                              invoice.status === "PAID"
                                ? "bg-accent text-accent-foreground"
                                : "bg-muted text-foreground/80",
                            )}
                          >
                            {t(`billing.invoiceStatus.${invoice.status}` as "billing.invoiceStatus.PAID")}
                          </span>
                        </td>
                        <td className="py-2.5 pr-4 pl-2.5 text-right font-bold md:pr-5">
                          {format.taka(invoice.amount)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            )}
          </AdminCard>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          <AdminCard title={text.business.owner}>
            {owner ? (
              <>
                <div className="flex items-center gap-3">
                  <span
                    aria-hidden="true"
                    className="flex size-11 flex-none items-center justify-center rounded-full bg-accent text-lg font-bold text-accent-foreground"
                  >
                    {owner.name.slice(0, 1)}
                  </span>
                  <span className="min-w-0">
                    <span className="block text-[17px] font-bold break-words">{owner.name}</span>
                    <span className="text-sm text-muted-foreground">{text.business.userId(owner.userId)}</span>
                  </span>
                </div>
                <ul className="flex flex-col">
                  {owner.phone && (
                    <li>
                      <a
                        href={`tel:${owner.phone}`}
                        className="flex min-h-10 items-center gap-2.5 text-[15px] font-semibold no-underline"
                      >
                        <Phone aria-hidden="true" className="size-4.5" />
                        {format.phone(owner.phone)}
                      </a>
                    </li>
                  )}
                  {owner.email && (
                    <li>
                      <a
                        href={`mailto:${owner.email}`}
                        className="flex min-h-10 items-center gap-2.5 text-[15px] font-semibold break-all no-underline"
                      >
                        <Mail aria-hidden="true" className="size-4.5 flex-none" />
                        {owner.email}
                      </a>
                    </li>
                  )}
                </ul>
              </>
            ) : (
              <p className="text-muted-foreground">{text.business.noOwner}</p>
            )}
            {(business.phone || business.address) && (
              <p className="text-sm text-muted-foreground">
                {[business.phone && text.business.shop(format.phone(business.phone)), business.address]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
            )}
          </AdminCard>

          <AdminCard title={text.business.actions}>
            {adminActions.length === 0 ? (
              <p className="text-muted-foreground">{text.business.noActions}</p>
            ) : (
              <AuditEntries entries={adminActions} />
            )}
          </AdminCard>
        </div>
      </div>

      {dialog === "suspend" && <SuspendDialog id={businessId} name={business.name} onClose={close} />}
      {dialog === "trial" && <TrialDialog id={businessId} onClose={close} />}
      {dialog === "plan" && <PlanDialog id={businessId} name={business.name} onClose={close} />}
    </div>
  );
}

function SuspendDialog({ id, name, onClose }: { id: string; name: string; onClose: () => void }) {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const [reason, setReason] = useState("");
  const [missing, setMissing] = useState(false);
  const suspend = useAdminChange(
    (why: string) =>
      call(api.POST("/api/v1/admin/businesses/{id}/suspend", { params: { path: { id } }, body: { reason: why } })),
    text.suspend.done,
    onClose,
  );
  return (
    <AdminDialog
      title={text.suspend.title(name)}
      action={text.suspend.action}
      danger
      problem={suspend.error ? errorMessages(suspend.error) : undefined}
      pending={suspend.isPending}
      onClose={onClose}
      onSubmit={() => {
        const why = reason.trim();
        setMissing(!why);
        if (why) {
          suspend.mutate(why);
        }
      }}
    >
      <ul className="list-disc pl-5 text-[15px] leading-relaxed text-foreground/80">
        {text.suspend.effects.map((effect) => (
          <li key={effect}>{effect}</li>
        ))}
      </ul>
      <AreaField
        label={text.suspend.reason}
        hint={text.suspend.reasonHint}
        rows={3}
        maxLength={500}
        value={reason}
        error={missing ? t("field.required") : undefined}
        onChange={(event) => setReason(event.target.value)}
      />
    </AdminDialog>
  );
}

function TrialDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const [days, setDays] = useState("7");
  const [wrong, setWrong] = useState(false);
  const extend = useAdminChange(
    (count: number) =>
      call(api.POST("/api/v1/admin/businesses/{id}/extend-trial", { params: { path: { id } }, body: { days: count } })),
    text.trial.done,
    onClose,
  );
  return (
    <AdminDialog
      title={text.trial.action}
      action={text.trial.action}
      problem={extend.error ? errorMessages(extend.error) : undefined}
      pending={extend.isPending}
      onClose={onClose}
      onSubmit={() => {
        const count = parseCount(days);
        const valid = count !== null && count >= 1 && count <= 90;
        setWrong(!valid);
        if (valid) {
          extend.mutate(count);
        }
      }}
    >
      <Field
        label={text.trial.days}
        inputMode="numeric"
        value={days}
        error={wrong ? t("field.countInvalid") : undefined}
        onChange={(event) => setDays(event.target.value)}
      />
    </AdminDialog>
  );
}

function monthsAhead(day: string, months: number): string {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() + months);
  return date.toISOString().slice(0, 10);
}

function PlanDialog({ id, name, onClose }: { id: string; name: string; onClose: () => void }) {
  const text = useAdminTexts();
  const today = dhakaToday();
  const { data: plans } = useQuery(plansQuery);
  // A paid plan until a date; Free has no period, so the owner moves to it, not an admin.
  const choices = plans?.filter((plan) => plan.priceMonthly > 0) ?? [];
  const [planCode, setPlanCode] = useState<string>();
  const [until, setUntil] = useState(monthsAhead(today, 1));
  const [reason, setReason] = useState("");
  const chosen = planCode ?? choices[0]?.code;
  const quick = [
    { label: text.setPlan.oneMonth, day: monthsAhead(today, 1) },
    { label: text.setPlan.threeMonths, day: monthsAhead(today, 3) },
    { label: text.setPlan.oneYear, day: monthsAhead(today, 12) },
  ];
  const set = useAdminChange(
    () =>
      call(
        api.PUT("/api/v1/admin/businesses/{id}/plan", {
          params: { path: { id } },
          // The whole of that day in Dhaka.
          body: {
            planCode: chosen ?? "",
            periodEnd: new Date(`${until}T23:59:59+06:00`).toISOString(),
            reason: reason.trim() || undefined,
          },
        }),
      ),
    text.setPlan.done,
    onClose,
  );
  return (
    <AdminDialog
      title={text.setPlan.action}
      description={text.setPlan.description(name)}
      action={text.setPlan.action}
      problem={set.error ? errorMessages(set.error) : undefined}
      pending={set.isPending || !chosen}
      onClose={onClose}
      onSubmit={() => set.mutate(undefined)}
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-semibold">{text.setPlan.plan}</legend>
        <div className="grid grid-cols-2 gap-2">
          {choices.map((plan) => (
            <label
              key={plan.code}
              className={cn(
                "flex min-h-12 cursor-pointer items-center gap-2 rounded-xl border-[1.5px] px-3 text-[15px]",
                chosen === plan.code ? "border-primary bg-accent font-bold" : "border-input",
              )}
            >
              <input
                type="radio"
                name="plan"
                value={plan.code}
                checked={chosen === plan.code}
                onChange={() => setPlanCode(plan.code)}
                className="size-4.5 accent-primary"
              />
              {plan.nameEn}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="flex flex-col gap-2">
        {/* biome-ignore lint/a11y/useSemanticElements: three buttons that fill in the date below. */}
        <div role="group" aria-label={text.setPlan.quick} className="flex gap-1.5">
          {quick.map((choice) => (
            <button
              key={choice.label}
              type="button"
              aria-pressed={until === choice.day}
              onClick={() => setUntil(choice.day)}
              className={cn(
                "h-9.5 flex-1 cursor-pointer rounded-full border-[1.5px] text-sm font-bold",
                until === choice.day
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-input hover:bg-muted",
              )}
            >
              {choice.label}
            </button>
          ))}
        </div>
        <Field
          label={text.setPlan.until}
          type="date"
          min={addDays(today, 1)}
          value={until}
          onChange={(event) => setUntil(event.target.value)}
        />
      </div>
      <Field
        label={text.setPlan.reason}
        placeholder={text.setPlan.reasonHint}
        autoComplete="off"
        maxLength={500}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
      />
    </AdminDialog>
  );
}
