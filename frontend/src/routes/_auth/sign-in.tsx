import { createFileRoute } from "@tanstack/react-router";
import { SignInPage } from "@/features/auth/SignInPage";
import { validateSignInSearch } from "@/lib/redirect";

export const Route = createFileRoute("/_auth/sign-in")({
  validateSearch: validateSignInSearch,
  component: SignInPage,
});
