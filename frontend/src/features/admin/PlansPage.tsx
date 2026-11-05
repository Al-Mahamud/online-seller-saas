import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka, takaInput } from "@/lib/money";
import { cn } from "@/lib/utils";
import { AdminHero } from "./AdminParts";
import { type AdminPlan, metricsQuery, plansQuery } from "./api";
import { useAdminTexts } from "./texts";

/** The limits a plan card shows, in this order; the editor shows every limit the plan has. */
const SHOWN = ["variants", "orders_per_month", "staff_seats"] as const;
const ORDER = [
  "variants",
  "orders_per_month",
  "staff_seats",
  "report_days",
  "import_rows",
  "inbox_channels",
  "ai_replies",
];

export function PlansPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { data: plans } = useQuery(plansQuery);
  const { data: metrics } = useQuery(metricsQuery);
  const [editing, setEditing] = useState<AdminPlan>();
  // The plans sellers can choose, cheapest first; hidden ones (such as the old trial) after them.
  const ordered = [...(plans ?? [])].sort(
    (a, b) => Number(b.isPublic) - Number(a.isPublic) || a.priceMonthly - b.priceMonthly,
  );
  const counts = new Map(metrics?.plans.map((plan) => [plan.code, plan.businesses]));
  const limitText = (limit: number | null | undefined) =>
    limit === null || limit === undefined ? text.plans.noLimit : format.number(limit);
  return (
    <div className="flex flex-col gap-4">
      <AdminHero title={text.nav.plans} subtitle={text.plans.hint} />
      <ul className="grid gap-3.5 md:grid-cols-2 xl:grid-cols-3">
        {ordered.map((plan) => (
          <li
            key={plan.code}
            className={cn(
              "flex flex-col gap-3.5 rounded-2xl border bg-card p-4.5 md:p-5",
              editing?.code === plan.code && "border-2 border-primary",
            )}
          >
            <div className="flex items-start justify-between gap-2.5">
              <div className="min-w-0">
                <h2 className="text-[22px] font-bold break-words">{plan.nameBn}</h2>
                <p className="text-sm text-muted-foreground">{plan.nameEn}</p>
              </div>
              <span
                className={cn(
                  "rounded-full px-2.5 text-[13px] leading-6 font-bold whitespace-nowrap",
                  plan.isPublic ? "bg-accent text-accent-foreground" : "bg-muted text-foreground/80",
                )}
              >
                {plan.isPublic ? text.plans.public : text.plans.hidden}
              </span>
            </div>
            <p className="flex items-baseline gap-1.5">
              <span className="text-[32px] leading-none font-bold">{format.taka(plan.priceMonthly)}</span>
              <span className="text-[15px] text-muted-foreground">
                {plan.priceMonthly > 0 ? text.plans.perMonth : text.plans.free}
              </span>
            </p>
            {counts.has(plan.code) && (
              <p className="rounded-[10px] bg-muted/70 px-3 py-2 text-sm text-foreground/80">
                {text.plans.on(format.number(counts.get(plan.code) ?? 0))}
              </p>
            )}
            <dl className="flex flex-col">
              {SHOWN.map((metric) => (
                <div key={metric} className="flex justify-between gap-2.5 border-t border-muted py-2 text-[15px]">
                  <dt className="text-foreground/80">{text.plans.limit[metric]}</dt>
                  <dd className="font-bold">{limitText(plan.limits[metric])}</dd>
                </div>
              ))}
              <div className="flex justify-between gap-2.5 border-t border-muted py-2 text-[15px]">
                <dt className="text-foreground/80">{text.plans.limit.report_days}</dt>
                <dd className="font-bold">
                  {plan.limits.report_days === null || plan.limits.report_days === undefined
                    ? text.plans.allTime
                    : text.plans.reports(format.number(plan.limits.report_days))}
                </dd>
              </div>
              <div className="flex justify-between gap-2.5 border-t border-muted py-2 text-[15px]">
                <dt className="text-foreground/80">{text.plans.downloads}</dt>
                <dd className="font-bold">{plan.exports ? text.plans.yes : text.plans.no}</dd>
              </div>
            </dl>
            <Button
              variant="outline"
              className="mt-auto h-11 rounded-xl border-[1.5px] border-primary text-[15px] font-bold text-primary"
              aria-label={`${text.plans.edit}: ${plan.nameEn}`}
              aria-expanded={editing?.code === plan.code}
              onClick={() => setEditing(plan)}
            >
              {text.plans.edit}
            </Button>
          </li>
        ))}
      </ul>
      {editing && <PlanEditor key={editing.code} plan={editing} onClose={() => setEditing(undefined)} />}
    </div>
  );
}

function PlanEditor({ plan, onClose }: { plan: AdminPlan; onClose: () => void }) {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const queryClient = useQueryClient();
  const section = useRef<HTMLElement>(null);
  const [nameBn, setNameBn] = useState(plan.nameBn);
  const [nameEn, setNameEn] = useState(plan.nameEn);
  const [price, setPrice] = useState(takaInput(plan.priceMonthly));
  const [isPublic, setIsPublic] = useState(plan.isPublic);
  const [exports, setExports] = useState(plan.exports);
  const [limits, setLimits] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      Object.entries(plan.limits).map(([metric, limit]) => [metric, limit === null ? "" : String(limit)]),
    ),
  );
  const [wrong, setWrong] = useState<Record<string, string>>({});
  // Opening the editor brings it into view, below the cards.
  useEffect(() => {
    section.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, []);
  const save = useMutation({
    mutationFn: (body: { priceMonthly: number; limits: Record<string, number | null> }) =>
      call(
        api.PUT("/api/v1/admin/plans/{code}", {
          params: { path: { code: plan.code } },
          body: {
            nameBn: nameBn.trim(),
            nameEn: nameEn.trim(),
            isPublic,
            exports,
            priceMonthly: body.priceMonthly,
            // An empty box is no limit, which the server takes as null.
            limits: body.limits as Record<string, number>,
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      void queryClient.invalidateQueries(plansQuery);
      void queryClient.invalidateQueries({ queryKey: ["plans"] });
      toast.success(text.plans.saved);
      onClose();
    },
  });
  function submit() {
    const problems: Record<string, string> = {};
    const priceMonthly = parseTaka(price);
    if (priceMonthly === null) {
      problems.price = t("field.amountInvalid");
    }
    if (!nameBn.trim()) {
      problems.nameBn = t("field.required");
    }
    if (!nameEn.trim()) {
      problems.nameEn = t("field.required");
    }
    const parsed: Record<string, number | null> = {};
    for (const [metric, value] of Object.entries(limits)) {
      const count = value.trim() ? parseCount(value) : null;
      if (value.trim() && count === null) {
        problems[metric] = t("field.countInvalid");
      }
      parsed[metric] = count;
    }
    setWrong(problems);
    if (Object.keys(problems).length === 0 && priceMonthly !== null) {
      save.mutate({ priceMonthly, limits: parsed });
    }
  }
  const metrics = Object.keys(limits).sort((a, b) => {
    const rank = (metric: string) => (ORDER.includes(metric) ? ORDER.indexOf(metric) : ORDER.length);
    return rank(a) - rank(b) || a.localeCompare(b);
  });
  return (
    <section
      ref={section}
      aria-labelledby="plan-editor"
      className="flex scroll-mb-6 flex-col gap-3.5 rounded-2xl border-2 border-primary bg-card p-4.5 md:p-5"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2.5">
        <h2 id="plan-editor" className="text-lg font-bold">
          {text.plans.editing(plan.nameEn)}
        </h2>
        <p className="text-sm text-muted-foreground">{text.plans.limitsHint}</p>
      </div>
      {save.error && (
        <Alert variant="destructive">
          <AlertDescription>{errorMessages(save.error)}</AlertDescription>
        </Alert>
      )}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field
          label={text.plans.nameBn}
          value={nameBn}
          maxLength={50}
          error={wrong.nameBn}
          onChange={(event) => setNameBn(event.target.value)}
        />
        <Field
          label={text.plans.nameEn}
          value={nameEn}
          maxLength={50}
          error={wrong.nameEn}
          onChange={(event) => setNameEn(event.target.value)}
        />
        <Field
          label={text.plans.price}
          inputMode="decimal"
          value={price}
          error={wrong.price}
          onChange={(event) => setPrice(event.target.value)}
        />
        {metrics.map((metric) => (
          <Field
            key={metric}
            label={text.plans.limit[metric] ?? metric}
            inputMode="numeric"
            value={limits[metric] ?? ""}
            error={wrong[metric]}
            onChange={(event) => setLimits({ ...limits, [metric]: event.target.value })}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-6">
        <Label className="min-h-11 text-base font-normal">
          <input
            type="checkbox"
            className="size-5 accent-primary"
            checked={isPublic}
            onChange={(event) => setIsPublic(event.target.checked)}
          />
          {text.plans.isPublic}
        </Label>
        <Label className="min-h-11 text-base font-normal">
          <input
            type="checkbox"
            className="size-5 accent-primary"
            checked={exports}
            onChange={(event) => setExports(event.target.checked)}
          />
          {text.plans.allowExports}
        </Label>
      </div>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" className="h-11 px-4 text-[15px] font-bold" onClick={onClose}>
          {text.plans.cancel}
        </Button>
        <Button className="h-11 px-4.5 text-[15px] font-bold" disabled={save.isPending} onClick={submit}>
          {text.plans.save}
        </Button>
      </div>
    </section>
  );
}
