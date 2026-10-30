import { createFileRoute } from "@tanstack/react-router";
import { InvitePage } from "@/features/staff/InvitePage";

/** The link in an invite, signed in or not. */
export const Route = createFileRoute("/invites")({
  component: InvitePage,
});
