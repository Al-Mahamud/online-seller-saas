import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { AddressPagination } from "@/components/layout/Pagination";
import { useFormat } from "@/lib/format";
import { pagedQuery } from "@/lib/paging";

const route = getRouteApi("/_app/settings/audit-log");

/** Who did what in the business, newest first (owners). */
export function AuditLogPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const paging = route.useSearch();
  const log = useQuery(
    pagedQuery(["audit-log", paging], () => call(api.GET("/api/v1/business/audit-log", { params: { query: paging } }))),
  );
  const entries = log.data?.items ?? [];
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <div>
        <BackToMore />
        <PageHeader title={t("audit.title")} />
      </div>
      <ul className="flex flex-col rounded-xl border bg-card px-4">
        {entries.map((entry) => (
          <li key={entry.id} className="border-t py-3 first:border-t-0">
            {/* An action added later and not yet translated still shows, by its code. */}
            <p className="font-medium">{t(`audit.action.${entry.action}` as never, { defaultValue: entry.action })}</p>
            <p className="text-sm text-muted-foreground">
              {entry.actor?.name ?? t("audit.system")} · {format.dateTime(entry.at)}
            </p>
          </li>
        ))}
      </ul>
      <AddressPagination result={log.data} />
    </div>
  );
}
