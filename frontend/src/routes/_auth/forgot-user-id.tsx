import { createFileRoute } from "@tanstack/react-router";
import { ForgotUserIdPage } from "@/features/auth/ForgotUserIdPage";

export const Route = createFileRoute("/_auth/forgot-user-id")({
  component: ForgotUserIdPage,
});
