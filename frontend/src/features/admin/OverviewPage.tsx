import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Clock, Landmark, MinusCircle } from "lucide-react";
import type { ReactNode } from "react";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { AdminCard, AdminHero, HeroFigure, PlanBadge, usePlanNames } from "./AdminParts";
import { metricsQuery, newestBusinessesQuery } from "./api";
import { useAdminTexts } from "./texts";

const SPLIT_COLOUR: Record<string, string> = { FREE: "bg-[#c9cfcb]", STARTER: "bg-[#2e8b6e]", PRO: "bg-ledger" };

function Tile({ label, value, note, good }: { label: string; value: string; note: string; good?: boolean }) {
  return (
    <div className="flex flex-col gap-1 rounded-2xl border bg-card p-4">
      <dt className="text-sm font-semibold text-muted-foreground">{label}</dt>
      <dd className="text-[26px] leading-tight font-bold">{value}</dd>
      <dd className={cn("text-sm", good ? "font-semibold text-primary" : "text-muted-foreground")}>{note}</dd>
    </div>
  );
}

function NeedsItem({
  tone,
  icon,
  title,
  value,
  note,
  link,
}: {
  tone: "gold" | "grey" | "red";
  icon: ReactNode;
  title: string;
  value: string;
  note: string;
  link?: ReactNode;
}) {
  return (
    <li
      className={cn(
        "flex flex-col gap-2 rounded-2xl p-4",
        tone === "gold" && "bg-[#fdf3e1]",
        tone === "grey" && "bg-muted",
        tone === "red" && "bg-danger-soft",
      )}
    >
      <span
        className={cn(
          "flex items-center gap-2 text-[15px] font-bold [&_svg]:size-5",
          tone === "gold" && "text-[#6b4300]",
          tone === "grey" && "text-foreground/80",
          tone === "red" && "text-danger-ink",
        )}
      >
        {icon}
        {title}
      </span>
      <span className="text-[28px] leading-none font-bold">{value}</span>
      <span className="text-sm text-muted-foreground">{note}</span>
      {link}
    </li>
  );
}

const SOLID_LINK =
  "inline-flex min-h-10 w-fit items-center rounded-[10px] bg-ledger px-3.5 text-[15px] font-bold text-white no-underline hover:bg-ledger/90";
const OUTLINE_LINK =
  "inline-flex min-h-10 w-fit items-center rounded-[10px] border-[1.5px] border-primary px-3.5 text-[15px] font-bold text-primary no-underline hover:bg-accent";

/** What the whole platform looks like now, and what is waiting for an admin. */
export function OverviewPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const planName = usePlanNames();
  const { data } = useQuery(metricsQuery);
  const { data: newest } = useQuery(newestBusinessesQuery);
  if (!data) {
    return null;
  }
  const count = format.number;
  const plans = data.plans.filter((plan) => plan.businesses > 0 || ["FREE", "STARTER", "PRO"].includes(plan.code));
  const onPlans = Math.max(
    1,
    plans.reduce((sum, plan) => sum + plan.businesses, 0),
  );
  const activeShare = data.businesses === 0 ? 0 : Math.round((data.weeklyActive / data.businesses) * 100);
  return (
    <div className="flex flex-col gap-4 md:gap-5">
      <AdminHero
        title={text.nav.overview}
        subtitle={text.overview.at(format.dateTime(data.at))}
        aside={
          <dl className="flex flex-wrap gap-x-7 gap-y-2">
            <HeroFigure label={text.overview.mrr} value={format.taka(data.mrrPoisha)} gold />
            <HeroFigure label={text.overview.paying} value={text.overview.businessCount(count(data.paying))} />
          </dl>
        }
      />

      <AdminCard title={text.overview.needs}>
        <ul className="grid gap-3 md:grid-cols-3">
          <NeedsItem
            tone="gold"
            icon={<Landmark aria-hidden="true" />}
            title={text.overview.transfers}
            value={text.overview.count(count(data.waitingTransfers))}
            note={
              data.oldestWaiting
                ? text.overview.transfersNote(format.taka(data.waitingPoisha), format.date(data.oldestWaiting))
                : text.overview.transfersNone
            }
            link={
              data.waitingTransfers > 0 && (
                <Link to="/admin/invoices" className={SOLID_LINK}>
                  {text.overview.check}
                </Link>
              )
            }
          />
          <NeedsItem
            tone="grey"
            icon={<Clock aria-hidden="true" />}
            title={text.overview.behind}
            value={text.overview.count(count(data.grace + data.readOnly))}
            note={text.overview.behindNote(count(data.grace), count(data.readOnly))}
            link={
              data.grace + data.readOnly > 0 && (
                <Link
                  to="/admin/businesses"
                  search={{ state: data.grace > 0 ? "GRACE" : "READ_ONLY" }}
                  className={OUTLINE_LINK}
                >
                  {text.overview.seeList}
                </Link>
              )
            }
          />
          <NeedsItem
            tone="red"
            icon={<MinusCircle aria-hidden="true" />}
            title={text.overview.suspended}
            value={text.overview.count(count(data.suspended))}
            note={text.overview.suspendedNote}
            link={
              data.suspended > 0 && (
                <Link to="/admin/businesses" search={{ state: "SUSPENDED" }} className={OUTLINE_LINK}>
                  {text.overview.seeList}
                </Link>
              )
            }
          />
        </ul>
      </AdminCard>

      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile
          label={text.overview.businesses}
          value={count(data.businesses)}
          note={text.overview.businessesNote(count(data.businesses7d))}
          good={data.businesses7d > 0}
        />
        <Tile
          label={text.overview.users}
          value={count(data.users)}
          note={text.overview.usersNote(count(data.signUps7d), count(data.signUps30d))}
        />
        <Tile
          label={text.overview.weeklyActive}
          value={count(data.weeklyActive)}
          note={text.overview.weeklyActiveNote(`${count(activeShare)}%`)}
        />
        <Tile
          label={text.overview.everPaid}
          value={count(data.everPaid)}
          note={text.overview.everPaidNote(count(data.paying))}
        />
      </dl>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1.6fr)]">
        <AdminCard title={text.overview.split}>
          <div aria-hidden="true" className="flex h-3.5 gap-0.5 overflow-hidden rounded-full">
            {plans
              .filter((plan) => plan.businesses > 0)
              .map((plan) => (
                <span
                  key={plan.code}
                  className={SPLIT_COLOUR[plan.code] ?? "bg-muted-foreground"}
                  style={{ flexGrow: plan.businesses }}
                />
              ))}
          </div>
          <ul className="flex flex-col gap-2.5">
            {plans.map((plan) => (
              <li key={plan.code} className="flex items-center gap-2.5 text-[15px]">
                <span
                  aria-hidden="true"
                  className={cn("size-3 rounded-[3px]", SPLIT_COLOUR[plan.code] ?? "bg-muted-foreground")}
                />
                <span className="flex-1">{planName(plan.code)}</span>
                <strong>{count(plan.businesses)}</strong>
                <span className="w-14 text-right text-muted-foreground">
                  {`${count(Math.round((plan.businesses / onPlans) * 100))}%`}
                </span>
              </li>
            ))}
          </ul>
        </AdminCard>

        <AdminCard
          title={text.overview.newest}
          action={
            <Link to="/admin/businesses" className="text-[15px] font-bold">
              {text.overview.seeAll}
            </Link>
          }
        >
          <ul className="-mt-1">
            {newest?.items.map(({ business, subscription }) => (
              <li key={business.id} className="flex items-center gap-3 border-t py-2.5 first:border-t-0">
                <span
                  aria-hidden="true"
                  className="flex size-9.5 flex-none items-center justify-center rounded-[10px] bg-accent font-bold text-accent-foreground"
                >
                  {business.name.slice(0, 1)}
                </span>
                <span className="min-w-0 flex-1">
                  <Link
                    to="/admin/businesses/$businessId"
                    params={{ businessId: business.id }}
                    className="block truncate font-bold text-foreground no-underline hover:underline"
                  >
                    {business.name}
                  </Link>
                  <span className="block truncate text-sm text-muted-foreground">
                    {[business.owner?.name, format.dateTime(business.createdAt)].filter(Boolean).join(" · ")}
                  </span>
                </span>
                {subscription && <PlanBadge code={subscription.planCode} />}
              </li>
            ))}
          </ul>
        </AdminCard>
      </div>
    </div>
  );
}
