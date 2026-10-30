import { createFileRoute } from "@tanstack/react-router";
import { SignUpPage } from "@/features/auth/SignUpPage";
import { validateRedirectSearch } from "@/lib/redirect";

export const Route = createFileRoute("/_auth/sign-up")({
  validateSearch: validateRedirectSearch,
  component: SignUpPage,
});
