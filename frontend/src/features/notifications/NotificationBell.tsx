import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Bell } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useFormat } from "@/lib/format";
import { unreadQuery } from "./api";

const MOST_SHOWN = 99;

function useCount() {
  const format = useFormat();
  const { data: unread = 0 } = useQuery(unreadQuery);
  return { unread, shown: unread > MOST_SHOWN ? `${format.number(MOST_SHOWN)}+` : format.number(unread) };
}

function Count({ shown }: { shown: string }) {
  return (
    <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-xs font-bold text-primary-foreground">
      {shown}
    </span>
  );
}

/** In the phone's header: the bell, with how many are unread on it. */
export function NotificationBell() {
  const { t } = useTranslation();
  const { unread, shown } = useCount();
  return (
    <Link
      to="/notifications"
      aria-label={unread > 0 ? t("notifications.unreadCount", { count: shown }) : t("notifications.title")}
      className="relative flex size-11 items-center justify-center text-foreground"
    >
      <Bell aria-hidden="true" className="size-5.5" />
      {unread > 0 && (
        <span className="absolute top-0.5 right-0">
          <Count shown={shown} />
        </span>
      )}
    </Link>
  );
}

/** In the sidebar: the same, with its name beside it. */
export function NotificationLink() {
  const { t } = useTranslation();
  const { unread, shown } = useCount();
  return (
    <Link
      to="/notifications"
      className="flex min-h-11 items-center gap-2 rounded-lg px-3 text-[#2B342F] no-underline hover:bg-muted data-[status=active]:bg-accent data-[status=active]:font-semibold data-[status=active]:text-accent-foreground"
    >
      <Bell aria-hidden="true" className="size-4.5" />
      <span className="flex-1">{t("notifications.title")}</span>{" "}
      {unread > 0 && (
        <>
          <Count shown={shown} /> <span className="sr-only">{t("notifications.unread")}</span>
        </>
      )}
    </Link>
  );
}
