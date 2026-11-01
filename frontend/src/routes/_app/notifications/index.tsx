import { createFileRoute } from "@tanstack/react-router";
import { NotificationsPage } from "@/features/notifications/NotificationsPage";
import { pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/_app/notifications/")({
  validateSearch: pagingSearch,
  component: NotificationsPage,
});
