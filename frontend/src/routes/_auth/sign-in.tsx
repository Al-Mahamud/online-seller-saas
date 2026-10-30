import { createFileRoute } from "@tanstack/react-router";
import { SignInPage } from "@/features/auth/SignInPage";
import { validateRedirectSearch } from "@/lib/redirect";

export const Route = createFileRoute("/_auth/sign-in")({
  validateSearch: validateRedirectSearch,
  component: SignInPage,
});
