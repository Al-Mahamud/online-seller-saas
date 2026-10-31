import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Field, SelectBox } from "@/components/form/Field";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { addDays, dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { parseCount } from "@/lib/money";
import { AdminDialog } from "./AdminDialog";
import { AuditList } from "./AuditPage";
import { type AdminBusiness, businessQuery, plansQuery } from "./api";
import { statusText } from "./status";
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

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right break-words">{children}</dd>
    </div>
  );
}

export function BusinessPage() {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const format = useFormat();
  const { businessId } = route.useParams();
  const { data } = useQuery(businessQuery(businessId));
  const [dialog, setDialog] = useState<DialogName>();
  const close = () => setDialog(undefined);
  const lift = useAdminChange(
    () => call(api.POST("/api/v1/admin/businesses/{id}/unsuspend", { params: { path: { id: businessId } } })),
    text.suspend.lifted,
  );
  const back = (
    <Link to="/admin" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {text.nav.businesses}
    </Link>
  );
  if (!data) {
    return back;
  }
  const { business, billing, adminActions } = data;
  const suspended = billing.status === "SUSPENDED";
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <div>
        {back}
        <h1 className="text-2xl font-bold tracking-tight break-words">{business.name}</h1>
      </div>
      <div className="flex flex-wrap gap-2">
        {suspended ? (
          <Button variant="outline" disabled={lift.isPending} onClick={() => lift.mutate(undefined)}>
            {text.suspend.lift}
          </Button>
        ) : (
          <>
            <Button variant="outline" onClick={() => setDialog("suspend")}>
              {text.suspend.action}
            </Button>
            {billing.status === "TRIALING" && (
              <Button variant="outline" onClick={() => setDialog("trial")}>
                {text.trial.action}
              </Button>
            )}
            {/* Setting a plan makes a business active, so it isn't offered over a suspension. */}
            <Button variant="outline" onClick={() => setDialog("plan")}>
              {text.setPlan.action}
            </Button>
          </>
        )}
      </div>
      <div className="grid items-start gap-4 md:grid-cols-2">
        <Section title={text.business.owner}>
          <dl className="flex flex-col gap-1">
            {business.owner && <Row label={text.business.owner}>{business.owner.name}</Row>}
            {business.owner?.phone && <Row label={text.business.phone}>{format.phone(business.owner.phone)}</Row>}
            {business.owner?.email && <Row label={text.business.email}>{business.owner.email}</Row>}
            <Row label={text.business.members}>{format.number(business.members)}</Row>
            <Row label={text.business.created}>{format.date(business.createdAt)}</Row>
          </dl>
        </Section>
        <Section title={text.business.subscription}>
          <dl className="flex flex-col gap-1">
            <Row label={text.business.status}>{statusText(billing.status)}</Row>
            <Row label={text.business.plan}>{billing.planCode}</Row>
            {billing.trialEndsAt && <Row label={text.business.trialEnds}>{format.date(billing.trialEndsAt)}</Row>}
            {billing.currentPeriodEnd && (
              <Row label={text.business.periodEnds}>{format.date(billing.currentPeriodEnd)}</Row>
            )}
            {billing.pendingPlanCode && <Row label={text.business.pendingPlan}>{billing.pendingPlanCode}</Row>}
          </dl>
        </Section>
        <Section title={text.business.usage}>
          <dl className="flex flex-col gap-1">
            {Object.entries(billing.usage).map(([metric, usage]) => (
              <Row key={metric} label={text.plans.limit[metric] ?? metric}>
                {format.number(usage.used)} /{" "}
                {usage.limit === null ? text.business.noLimit : format.number(usage.limit)}
              </Row>
            ))}
          </dl>
        </Section>
        <Section title={text.business.invoices}>
          {billing.invoices.length === 0 && <p className="text-muted-foreground">{text.business.noInvoices}</p>}
          <ul className="flex flex-col">
            {billing.invoices.map((invoice) => (
              <li key={invoice.id} className="flex justify-between gap-3 border-t py-2 first:border-t-0">
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
              </li>
            ))}
          </ul>
        </Section>
      </div>
      <Section title={text.business.actions}>
        {adminActions.length === 0 ? (
          <p className="text-muted-foreground">{text.business.noActions}</p>
        ) : (
          <AuditList entries={adminActions} />
        )}
      </Section>
      {dialog === "suspend" && <SuspendDialog id={businessId} name={business.name} onClose={close} />}
      {dialog === "trial" && <TrialDialog id={businessId} onClose={close} />}
      {dialog === "plan" && <PlanDialog id={businessId} onClose={close} />}
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
      description={text.suspend.description}
      action={text.suspend.action}
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
      <Field
        label={text.suspend.reason}
        autoComplete="off"
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

function PlanDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const text = useAdminTexts();
  const today = dhakaToday();
  const { data: plans } = useQuery(plansQuery);
  const choices = plans?.filter((plan) => plan.code !== "TRIAL") ?? [];
  const [planCode, setPlanCode] = useState<string>();
  const [until, setUntil] = useState(addDays(today, 30));
  const chosen = planCode ?? choices[0]?.code;
  const set = useAdminChange(
    () =>
      call(
        api.PUT("/api/v1/admin/businesses/{id}/plan", {
          params: { path: { id } },
          // The whole of that day in Dhaka.
          body: { planCode: chosen ?? "", periodEnd: new Date(`${until}T23:59:59+06:00`).toISOString() },
        }),
      ),
    text.setPlan.done,
    onClose,
  );
  return (
    <AdminDialog
      title={text.setPlan.action}
      description={text.setPlan.description}
      action={text.setPlan.action}
      problem={set.error ? errorMessages(set.error) : undefined}
      pending={set.isPending || !chosen}
      onClose={onClose}
      onSubmit={() => set.mutate(undefined)}
    >
      <SelectBox label={text.setPlan.plan} value={chosen ?? ""} onChange={(event) => setPlanCode(event.target.value)}>
        {choices.map((plan) => (
          <option key={plan.code} value={plan.code}>
            {plan.nameBn} / {plan.nameEn}
          </option>
        ))}
      </SelectBox>
      <Field
        label={text.setPlan.until}
        type="date"
        min={today}
        value={until}
        onChange={(event) => setUntil(event.target.value)}
      />
    </AdminDialog>
  );
}
