import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { NotificationSettingsPage } from "@/features/notifications/NotificationSettingsPage";

/** The low-stock emails link here. */
export const Route = createFileRoute("/_app/settings/notifications")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  component: NotificationSettingsPage,
});
