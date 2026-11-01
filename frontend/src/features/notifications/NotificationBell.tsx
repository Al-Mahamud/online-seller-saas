import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Bell } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { unreadQuery } from "./api";

const MOST_SHOWN = 99;

function useCount() {
  const format = useFormat();
  const { data: unread = 0 } = useQuery(unreadQuery);
  return { unread, shown: unread > MOST_SHOWN ? `${format.number(MOST_SHOWN)}+` : format.number(unread) };
}

function Count({ shown, className }: { shown: string; className?: string }) {
  return (
    <span
      className={cn(
        "flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-xs font-bold text-primary-foreground",
        className,
      )}
    >
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
export function NotificationLink({ className }: { className: string }) {
  const { t } = useTranslation();
  const { unread, shown } = useCount();
  return (
    <Link to="/notifications" className={className}>
      <Bell aria-hidden="true" />
      <span className="flex-1">{t("notifications.title")}</span>{" "}
      {unread > 0 && (
        <>
          <Count shown={shown} className="bg-marigold-text text-ledger" />{" "}
          <span className="sr-only">{t("notifications.unread")}</span>
        </>
      )}
    </Link>
  );
}
