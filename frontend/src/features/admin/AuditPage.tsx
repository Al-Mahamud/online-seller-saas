import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { AddressPagination } from "@/components/layout/Pagination";
import { useFormat } from "@/lib/format";
import { type AuditEntry, auditQuery } from "./api";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/audit");

export function AuditList({ entries, withLinks }: { entries: AuditEntry[]; withLinks?: boolean }) {
  const text = useAdminTexts();
  const format = useFormat();
  return (
    <ul className="flex flex-col">
      {entries.map((entry) => (
        <li key={entry.id} className="border-t py-2 first:border-t-0">
          <p className="font-medium">{text.audit.action[entry.action] ?? entry.action}</p>
          {Object.entries(entry.details).map(([key, value]) => (
            <p key={key} className="text-sm break-words">
              {key}: {String(value)}
            </p>
          ))}
          <p className="text-sm text-muted-foreground">
            {format.dateTime(entry.at)}
            {entry.adminName && ` · ${entry.adminName}`}
          </p>
          {withLinks && entry.businessId && (
            <Link
              to="/admin/businesses/$businessId"
              params={{ businessId: entry.businessId }}
              className="flex min-h-11 w-fit items-center text-primary"
            >
              {text.audit.openBusiness}
            </Link>
          )}
        </li>
      ))}
    </ul>
  );
}

export function AuditPage() {
  const text = useAdminTexts();
  const { data } = useQuery(auditQuery(route.useSearch()));
  return (
    <div className="flex max-w-3xl flex-col gap-3">
      <h1 className="text-2xl font-bold tracking-tight">{text.nav.audit}</h1>
      {data?.items.length === 0 && <p className="py-4 text-muted-foreground">{text.audit.none}</p>}
      {!!data?.items.length && (
        <div className="rounded-xl border bg-card px-4">
          <AuditList entries={data.items} withLinks />
        </div>
      )}
      <AddressPagination result={data} />
    </div>
  );
}
