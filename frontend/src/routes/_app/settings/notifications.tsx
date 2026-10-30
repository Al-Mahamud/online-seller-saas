import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { NotificationSettingsPage } from "@/features/notifications/NotificationSettingsPage";

/** The low-stock emails link here. */
export const Route = createFileRoute("/_app/settings/notifications")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: NotificationSettingsPage,
});
