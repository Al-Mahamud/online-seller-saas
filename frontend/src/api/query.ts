import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import i18n from "@/i18n";
import { ApiError, errorAction } from "./errors";

/**
 * Errors every screen would show the same way become a toast here. The others are for the screen
 * (field problems under their inputs) or for the app as a whole: signing in (F1), choosing a
 * business (F2), the upgrade dialog and the read-only banner (F8).
 */
export function showError(error: unknown) {
  const action = errorAction(error);
  switch (action.kind) {
    case "network":
      toast.error(i18n.t("error.network"));
      return;
    case "rate-limited":
      toast.error(
        action.seconds ? i18n.t("error.rateLimited", { seconds: action.seconds }) : i18n.t("error.unexpected"),
      );
      return;
    case "message":
      toast.error(action.detail ?? i18n.t("error.unexpected"));
      return;
  }
}

/** A mutation that shows its own errors (a form with fields) sets `meta: { showsOwnErrors: true }`. */
declare module "@tanstack/react-query" {
  interface Register {
    mutationMeta: { showsOwnErrors?: boolean };
  }
}

export function createQueryClient() {
  return new QueryClient({
    queryCache: new QueryCache({ onError: showError }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (!mutation.meta?.showsOwnErrors) {
          showError(error);
        }
      },
    }),
    defaultOptions: {
      queries: {
        // Lists refresh when the seller comes back to the app; stock is never shown from an old copy.
        refetchOnWindowFocus: true,
        // A refused request (4xx) fails the same way again; network and server errors get two retries.
        retry: (failures, error) => failures < 2 && !(error instanceof ApiError && error.status < 500),
      },
    },
  });
}
