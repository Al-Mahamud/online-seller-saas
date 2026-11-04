import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { can, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { PageHeader } from "@/components/layout/PageHeader";
import { AddressPagination } from "@/components/layout/Pagination";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Notification, notificationLink, notificationsQuery } from "./api";

const route = getRouteApi("/_app/notifications/");

export function NotificationsPage() {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const list = useQuery(notificationsQuery(route.useSearch()));
  const items = list.data?.items ?? [];
  const unread = list.data?.unread ?? 0;
  const markRead = useMutation({
    mutationFn: (body: { ids: string[] } | { all: true }) => call(api.POST("/api/v1/notifications/read", { body })),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["notifications"] }),
  });
  return (
    <div className="flex max-w-2xl flex-col gap-3">
      <PageHeader title={t("notifications.title")}>
        {unread > 0 && (
          <Button variant="outline" disabled={markRead.isPending} onClick={() => markRead.mutate({ all: true })}>
            {t("notifications.markAllRead")}
          </Button>
        )}
      </PageHeader>
      {list.isSuccess && items.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">{t("notifications.none")}</p>
      )}
      {items.length > 0 && (
        <ul className="flex flex-col rounded-xl border bg-card px-4">
          {items.map((notification) => (
            <Row
              key={notification.id}
              notification={notification}
              onRead={() => {
                if (!notification.read) {
                  markRead.mutate({ ids: [notification.id] });
                }
              }}
            />
          ))}
        </ul>
      )}
      <AddressPagination result={list.data} />
      {can(business, "SETTINGS") && (
        <Link to="/settings/notifications" className="flex min-h-11 w-fit items-center text-primary">
          {t("notifications.emailChoices")}
        </Link>
      )}
    </div>
  );
}

function Row({ notification, onRead }: { notification: Notification; onRead: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const link = notificationLink(notification);
  const body = (
    <>
      <span className={cn("block", !notification.read && "font-semibold")}>
        {!notification.read && <span className="sr-only">{t("notifications.new")}: </span>}
        {notification.title}
      </span>
      <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
        {!notification.read && <span aria-hidden="true" className="size-2 flex-none rounded-full bg-primary" />}
        {format.dateTime(notification.at)}
      </span>
    </>
  );
  const box = "block w-full py-3 text-left text-foreground no-underline";
  return (
    <li className="border-t first:border-t-0">
      {link ? (
        <Link {...link} className={box} onClick={onRead}>
          {body}
        </Link>
      ) : (
        // Nothing to go to: tapping it still says it has been seen.
        <button type="button" className={cn(box, "cursor-pointer")} onClick={onRead}>
          {body}
        </button>
      )}
    </li>
  );
}
