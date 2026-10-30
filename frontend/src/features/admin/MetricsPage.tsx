import { useQuery } from "@tanstack/react-query";
import { useFormat } from "@/lib/format";
import { metricsQuery } from "./api";
import { useAdminTexts } from "./texts";

function Tile({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="rounded-xl border bg-card p-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-2xl font-bold tracking-tight">{value}</dd>
      {note && <dd className="text-[13px] text-muted-foreground">{note}</dd>}
    </div>
  );
}

export function MetricsPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { data } = useQuery(metricsQuery);
  if (!data) {
    return null;
  }
  const count = format.number;
  return (
    <div className="flex max-w-4xl flex-col gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{text.nav.metrics}</h1>
        <p className="text-muted-foreground">{text.metrics.at(format.dateTime(data.at))}</p>
      </div>
      <dl className="grid grid-cols-2 gap-3 md:grid-cols-3">
        <Tile label={text.metrics.mrr} value={format.taka(data.mrrPoisha)} />
        <Tile
          label={text.metrics.paying}
          value={count(data.paying)}
          note={text.metrics.everPaid(count(data.everPaid))}
        />
        <Tile
          label={text.metrics.conversion}
          value={data.conversion === null ? "–" : `${count(Math.round(data.conversion * 100))}%`}
          note={data.conversion === null ? text.metrics.noConversion : undefined}
        />
        <Tile label={text.metrics.trialing} value={count(data.trialing)} />
        <Tile label={text.metrics.readOnly} value={count(data.readOnly)} />
        <Tile label={text.metrics.suspended} value={count(data.suspended)} />
        <Tile
          label={text.metrics.businesses}
          value={count(data.businesses)}
          note={text.metrics.businessesWeek(count(data.businesses7d))}
        />
        <Tile label={text.metrics.weeklyActive} value={count(data.weeklyActive)} />
        <Tile
          label={text.metrics.users}
          value={count(data.users)}
          note={text.metrics.signUps(count(data.signUps7d), count(data.signUps30d))}
        />
      </dl>
    </div>
  );
}
