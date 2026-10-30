import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka, takaInput } from "@/lib/money";
import { AdminDialog } from "./AdminDialog";
import { type AdminPlan, plansQuery } from "./api";
import { useAdminTexts } from "./texts";

export function PlansPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { data: plans } = useQuery(plansQuery);
  const [editing, setEditing] = useState<AdminPlan>();
  return (
    <div className="flex max-w-3xl flex-col gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{text.nav.plans}</h1>
        <p className="text-muted-foreground">{text.plans.hint}</p>
      </div>
      <ul className="grid gap-3 sm:grid-cols-2">
        {plans?.map((plan) => (
          <li key={plan.code} className="flex flex-col gap-2 rounded-xl border bg-card p-4">
            <div>
              <h2 className="text-lg font-semibold">
                {plan.nameBn} / {plan.nameEn}
              </h2>
              <p>
                <span className="font-bold">{format.taka(plan.priceMonthly)}</span> {text.plans.perMonth}
              </p>
              <p className="text-sm text-muted-foreground">
                {plan.isPublic ? text.plans.public : text.plans.hidden}
                {plan.exports && ` · ${text.plans.exports}`}
              </p>
            </div>
            <dl className="text-sm">
              {Object.entries(plan.limits)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([metric, limit]) => (
                  <div key={metric} className="flex justify-between gap-3">
                    <dt className="text-muted-foreground">{text.plans.limit[metric] ?? metric}</dt>
                    <dd>{limit === null ? "∞" : format.number(limit)}</dd>
                  </div>
                ))}
            </dl>
            <Button
              variant="outline"
              className="mt-auto self-start"
              aria-label={`${text.plans.edit}: ${plan.nameEn}`}
              onClick={() => setEditing(plan)}
            >
              {text.plans.edit}
            </Button>
          </li>
        ))}
      </ul>
      {editing && <PlanDialog key={editing.code} plan={editing} onClose={() => setEditing(undefined)} />}
    </div>
  );
}

function PlanDialog({ plan, onClose }: { plan: AdminPlan; onClose: () => void }) {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const queryClient = useQueryClient();
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
  return (
    <AdminDialog
      title={plan.nameEn}
      action={t("customers.save")}
      problem={save.error ? errorMessages(save.error) : undefined}
      pending={save.isPending}
      onClose={onClose}
      onSubmit={submit}
    >
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
      <p className="text-sm text-muted-foreground">{text.plans.limitsHint}</p>
      {Object.keys(limits)
        .sort()
        .map((metric) => (
          <Field
            key={metric}
            label={text.plans.limit[metric] ?? metric}
            inputMode="numeric"
            value={limits[metric] ?? ""}
            error={wrong[metric]}
            onChange={(event) => setLimits({ ...limits, [metric]: event.target.value })}
          />
        ))}
    </AdminDialog>
  );
}
