import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import i18n from "@/i18n";
import { reportError } from "@/lib/reporting";
import { ApiError, errorAction, NetworkError } from "./errors";

/** What to say about an error that has no better place: the server's message, or our own when it sent none. */
export function errorText(error: unknown): string {
  const action = errorAction(error);
  switch (action.kind) {
    case "network":
      return i18n.t("error.network");
    case "rate-limited":
      return action.seconds ? i18n.t("error.rateLimited", { seconds: action.seconds }) : i18n.t("error.unexpected");
    case "message":
      return action.detail ?? i18n.t("error.unexpected");
    case "fields":
      return action.detail || i18n.t("error.unexpected");
    default:
      return error instanceof ApiError && error.detail ? error.detail : i18n.t("error.unexpected");
  }
}

/** The same, and for refused fields what's wrong with each: for a screen with no field to put it under. */
export function errorMessages(error: unknown): string {
  const action = errorAction(error);
  return action.kind === "fields" ? action.fields.map((problem) => problem.message).join(" ") : errorText(error);
}

/**
 * Errors every screen would show the same way become a toast here. The others are for the screen
 * (field problems under their inputs) or for the app as a whole: choosing a business (F2), the
 * upgrade dialog.
 */
function showError(error: unknown) {
  const { kind } = errorAction(error);
  if (kind === "network" || kind === "rate-limited" || kind === "message") {
    toast.error(errorText(error));
  }
  // Every screen's requests can be refused together, and one message says it all.
  if (kind === "read-only") {
    toast.error(errorText(error), { id: "read-only" });
  }
}

/**
 * A query or mutation that shows its own errors (a form with fields) sets `meta: { showsOwnErrors: true }`.
 * A query the page does well enough without sets `meta: { optional: true }`: its failure is nobody's concern.
 */
declare module "@tanstack/react-query" {
  interface Register {
    queryMeta: { showsOwnErrors?: boolean; optional?: boolean };
    mutationMeta: { showsOwnErrors?: boolean };
  }
}

/**
 * `onSignedOut`: the server said the session is gone (expired, or signed out elsewhere).
 * `onAccessChanged`: the server said no business is selected, or refused on the role: a member's
 * business or role may have changed. `onPlanLimit`: the business's plan doesn't allow this.
 * `onSubscriptionRefused`: refused for being read-only or suspended, which the banner should now say.
 */
export function createQueryClient({
  onSignedOut,
  onAccessChanged,
  onPlanLimit,
  onSubscriptionRefused,
}: {
  onSignedOut?: () => void;
  onAccessChanged?: () => void;
  onPlanLimit?: (limit: { metric: string | null; limit: number | null }) => void;
  onSubscriptionRefused?: () => void;
} = {}) {
  const handle = (error: unknown, showsOwnErrors: boolean | undefined) => {
    // The server's refusals and lost connections are expected; anything else is a bug here.
    if (!(error instanceof ApiError) && !(error instanceof NetworkError)) {
      reportError(error);
    }
    const action = errorAction(error);
    const { kind } = action;
    if (kind === "sign-in") {
      onSignedOut?.();
      return;
    }
    if (action.kind === "upgrade") {
      onPlanLimit?.({ metric: action.metric, limit: action.limit });
    }
    if (kind === "read-only") {
      onSubscriptionRefused?.();
    }
    if (kind === "pick-business" || (error instanceof ApiError && error.status === 403)) {
      onAccessChanged?.();
    }
    if (!showsOwnErrors) {
      showError(error);
    }
  };
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (!query.meta?.optional) {
          handle(error, query.meta?.showsOwnErrors);
        }
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => handle(error, mutation.meta?.showsOwnErrors),
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
