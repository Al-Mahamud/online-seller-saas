import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { CheckCircle2, Clock, Landmark, Layers, MinusCircle, Pencil } from "lucide-react";
import type { ReactNode } from "react";
import { AddressPagination } from "@/components/layout/Pagination";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { AdminHero, HeroChips, usePlanNames } from "./AdminParts";
import { AUDIT_KINDS, type AuditEntry, type AuditKind, auditQuery } from "./api";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/audit");

const LOOK: Record<string, { icon: ReactNode; tint: string }> = {
  "invoice.marked_paid": { icon: <Landmark />, tint: "bg-accent text-accent-foreground" },
  "plan.set": { icon: <Layers />, tint: "bg-[#fdf3e1] text-[#6b4300]" },
  "plan.edited": { icon: <Pencil />, tint: "bg-muted text-foreground/80" },
  "business.suspended": { icon: <MinusCircle />, tint: "bg-danger-soft text-danger-ink" },
  "business.unsuspended": { icon: <CheckCircle2 />, tint: "bg-accent text-accent-foreground" },
  "trial.extended": { icon: <Clock />, tint: "bg-muted text-foreground/80" },
};

/** One admin action as a sentence: who did what to which business, and the details that matter. */
export function AuditEntries({ entries, withBusiness }: { entries: AuditEntry[]; withBusiness?: boolean }) {
  const text = useAdminTexts();
  const format = useFormat();
  const planName = usePlanNames();
  return (
    <ol>
      {entries.map((entry) => {
        const detail = (key: string) => {
          const value = entry.details[key];
          return value === undefined || value === null ? "" : String(value);
        };
        const notes: string[] = [];
        if (entry.action === "plan.set") {
          notes.push(
            [planName(detail("plan")), detail("until") && text.audit.until(format.date(detail("until")))]
              .filter(Boolean)
              .join(" · "),
          );
        }
        if (entry.action === "plan.edited" && detail("price")) {
          notes.push(format.taka(Number(detail("price"))));
        }
        if (entry.action === "invoice.marked_paid") {
          notes.push(
            [
              detail("number"),
              detail("amount") && format.taka(Number(detail("amount"))),
              detail("reference") && text.audit.reference(detail("reference")),
            ]
              .filter(Boolean)
              .join(" · "),
          );
        }
        if (entry.action === "trial.extended" && detail("days")) {
          notes.push(text.audit.days(format.number(Number(detail("days")))));
        }
        if (detail("reason")) {
          notes.push(text.audit.reason(detail("reason")));
        }
        const look = LOOK[entry.action] ?? { icon: <Pencil />, tint: "bg-muted text-foreground/80" };
        const verb = text.audit.action[entry.action] ?? entry.action;
        const subject =
          entry.action === "plan.edited" ? (
            <strong>{planName(entry.targetId)}</strong>
          ) : withBusiness && entry.businessId ? (
            <Link to="/admin/businesses/$businessId" params={{ businessId: entry.businessId }} className="font-bold">
              {entry.businessName ?? text.audit.openBusiness}
            </Link>
          ) : null;
        return (
          <li key={entry.id} className="flex gap-3 border-t border-muted py-3 first:border-t-0">
            <span className="w-17 flex-none pt-0.5 text-[13px] font-semibold text-muted-foreground md:w-19">
              {format.time(entry.at)}
            </span>
            <span
              aria-hidden="true"
              className={cn(
                "flex size-8.5 flex-none items-center justify-center rounded-[10px] [&_svg]:size-4.25",
                look.tint,
              )}
            >
              {look.icon}
            </span>
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <p className="text-[15px] leading-relaxed break-words">
                <strong>{entry.adminName ?? text.audit.someone}</strong>{" "}
                {text.audit.subjectFirst ? (
                  <>
                    {subject} {verb}
                  </>
                ) : (
                  <>
                    {verb} {subject}
                  </>
                )}
              </p>
              {notes.map((note) => (
                <p key={note} className="text-sm break-words text-foreground/80">
                  {note}
                </p>
              ))}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Entries grouped by their day in Dhaka, newest first, as they come. */
function byDay(entries: AuditEntry[]): [string, AuditEntry[]][] {
  const days = new Map<string, AuditEntry[]>();
  for (const entry of entries) {
    const day = dhakaToday(new Date(entry.at));
    days.set(day, [...(days.get(day) ?? []), entry]);
  }
  return [...days.entries()];
}

export function AuditPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { kind, ...paging } = route.useSearch();
  const navigate = route.useNavigate();
  const { data } = useQuery(auditQuery(kind, paging));
  const today = dhakaToday();
  return (
    <div className="flex flex-col gap-4">
      <AdminHero title={text.nav.audit} subtitle={text.audit.subtitle}>
        <HeroChips<AuditKind>
          label={text.audit.kinds}
          value={kind}
          onChange={(next) =>
            void navigate({ search: (prev) => ({ ...prev, kind: next, page: undefined }), replace: true })
          }
          options={[
            { value: undefined, label: text.audit.all },
            ...(Object.keys(AUDIT_KINDS) as AuditKind[]).map((value) => ({ value, label: text.audit.kind[value] })),
          ]}
        />
      </AdminHero>
      <section aria-label={text.nav.audit} className="rounded-2xl border bg-card px-4 py-2 md:px-5.5">
        {data?.items.length === 0 && (
          <p className="py-6 text-center text-muted-foreground">{kind ? text.audit.noneHere : text.audit.none}</p>
        )}
        {byDay(data?.items ?? []).map(([day, entries]) => (
          <div key={day}>
            <h2 className="mt-2.5 mb-1 font-bold">
              {day === today ? text.audit.today(format.fullDate(day)) : format.fullDate(day)}
            </h2>
            <AuditEntries entries={entries} withBusiness />
          </div>
        ))}
      </section>
      <AddressPagination result={data} />
    </div>
  );
}
