import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { type CodRow, codInTransitQuery, codOwedQuery, ordersChanged } from "./api";

interface Courier {
  name: string;
  owed: CodRow[];
  inTransit: number;
}

/** The same courier however it was typed: "pathao" and "Pathao" settle together, as on the server. */
function byCourier(owed: CodRow[], inTransit: CodRow[]): Courier[] {
  const couriers = new Map<string, Courier>();
  const entry = (name: string) => {
    const key = name.toLowerCase();
    let courier = couriers.get(key);
    if (!courier) {
      courier = { name, owed: [], inTransit: 0 };
      couriers.set(key, courier);
    }
    return courier;
  };
  for (const row of owed) {
    entry(row.courier).owed.push(row);
  }
  for (const row of inTransit) {
    entry(row.courier).inTransit += row.codExpected;
  }
  return [...couriers.values()];
}

const collected = (row: CodRow) => row.codCollected ?? 0;

/** What each courier owes the business, and marking its payout as received. */
export function CodPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const owed = useQuery(codOwedQuery);
  const inTransit = useQuery(codInTransitQuery);
  const couriers = byCourier(owed.data ?? [], inTransit.data ?? []);
  const owedTotal = (owed.data ?? []).reduce((sum, row) => sum + collected(row), 0);
  const transitTotal = (inTransit.data ?? []).reduce((sum, row) => sum + row.codExpected, 0);
  return (
    <div className="flex max-w-2xl flex-col gap-3">
      <div>
        <BackToMore />
        <PageHeader title={t("cod.title")} />
      </div>
      {owed.isSuccess && inTransit.isSuccess && couriers.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">{t("cod.none")}</p>
      )}
      {couriers.length > 0 && (
        <dl className="grid grid-cols-2 gap-3">
          <div className="rounded-xl border bg-card p-3">
            <dt className="text-sm text-muted-foreground">{t("cod.owed")}</dt>
            <dd className="text-xl font-bold">{format.taka(owedTotal)}</dd>
          </div>
          <div className="rounded-xl border bg-card p-3">
            <dt className="text-sm text-muted-foreground">{t("cod.inTransit")}</dt>
            <dd className="text-xl font-bold">{format.taka(transitTotal)}</dd>
          </div>
        </dl>
      )}
      {couriers.map((courier) => (
        <CourierSection key={courier.name.toLowerCase()} courier={courier} />
      ))}
    </div>
  );
}

function CourierSection({ courier }: { courier: Courier }) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const canSettle = hasRole(business, "MANAGER");
  // Everything is ticked at first: usually the payout covers all of it. Untick what it doesn't.
  const [left, setLeft] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const chosen = courier.owed.filter((row) => !left.has(row.id));
  const amount = chosen.reduce((sum, row) => sum + collected(row), 0);
  const settle = useMutation({
    mutationFn: () =>
      call(api.POST("/api/v1/shipments/settle-cod", { body: { shipmentIds: chosen.map((row) => row.id) } })),
    onSuccess: (done) => {
      toast.success(t("cod.settled", { amount: format.taka(done.amount), courier: done.courier }));
      setLeft(new Set());
      ordersChanged(queryClient);
    },
    onSettled: () => setConfirming(false),
  });
  return (
    <Section title={courier.name}>
      <p>
        {t("cod.courierOwes", {
          amount: format.taka(courier.owed.reduce((sum, row) => sum + collected(row), 0)),
          count: format.number(courier.owed.length),
        })}
        {courier.inTransit > 0 && (
          <span className="block text-sm text-muted-foreground">
            {t("cod.courierInTransit", { amount: format.taka(courier.inTransit) })}
          </span>
        )}
      </p>
      {courier.owed.length > 0 && (
        <ul className="flex flex-col">
          {courier.owed.map((row) => (
            <li key={row.id} className="flex items-center gap-2 border-t py-1 first:border-t-0">
              {canSettle && (
                <label className="flex size-11 flex-none cursor-pointer items-center justify-center">
                  <input
                    type="checkbox"
                    className="size-5 accent-primary"
                    aria-label={t("orders.selectOne", { number: format.orderNumber(row.orderNumberLabel) })}
                    checked={!left.has(row.id)}
                    onChange={(event) => {
                      const next = new Set(left);
                      if (event.target.checked) {
                        next.delete(row.id);
                      } else {
                        next.add(row.id);
                      }
                      setLeft(next);
                    }}
                  />
                </label>
              )}
              <span className="min-w-0 flex-1">
                <Link to="/orders/$orderId" params={{ orderId: row.orderId }} className="font-medium text-primary">
                  {format.orderNumber(row.orderNumberLabel)}
                </Link>
                <span className="block text-sm text-muted-foreground">
                  {[row.trackingNo, row.deliveredAt && format.date(row.deliveredAt)].filter(Boolean).join(" · ")}
                </span>
              </span>
              <span className="flex-none font-semibold">{format.taka(collected(row))}</span>
            </li>
          ))}
        </ul>
      )}
      {canSettle && courier.owed.length > 0 && (
        <Button disabled={chosen.length === 0} className="self-start" onClick={() => setConfirming(true)}>
          {t("cod.settle", { amount: format.taka(amount) })}
        </Button>
      )}
      {confirming && (
        <ConfirmDialog
          title={t("cod.settleTitle", { courier: courier.name })}
          description={t("cod.settleDescription", { amount: format.taka(amount), count: format.number(chosen.length) })}
          confirm={t("cod.settle", { amount: format.taka(amount) })}
          pending={settle.isPending}
          onConfirm={() => settle.mutate()}
          onClose={() => setConfirming(false)}
        />
      )}
    </Section>
  );
}
