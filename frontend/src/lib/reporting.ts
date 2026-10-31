import type { ErrorEvent } from "@sentry/browser";

/**
 * Unexpected errors go to Sentry, only when the build was given a DSN. The library loads after the page,
 * and only then, so it never delays the first screen.
 */
const DSN = import.meta.env.VITE_SENTRY_DSN as string | undefined;

const LONG_NUMBER = /\+?\d[\d -]{8,}\d/g;

function clean(text: string): string {
  return text.replace(LONG_NUMBER, "[number]");
}

/**
 * Nothing about customers leaves the phone. An address can hold a phone number being searched for, or
 * an invite's secret after its #; an error's message can quote what someone typed.
 */
export function scrubEvent(event: ErrorEvent): ErrorEvent {
  if (event.request?.url) {
    event.request.url = event.request.url.split(/[?#]/)[0] as string;
  }
  if (event.request?.headers) {
    delete event.request.headers.Referer;
  }
  for (const exception of event.exception?.values ?? []) {
    if (exception.value) {
      exception.value = clean(exception.value);
    }
  }
  if (event.message) {
    event.message = clean(event.message);
  }
  return event;
}

let started: Promise<typeof import("@sentry/browser")> | null = null;

function sentry() {
  started ??= import("@sentry/browser").then((Sentry) => {
    Sentry.init({
      dsn: DSN,
      environment: import.meta.env.VITE_SENTRY_ENVIRONMENT ?? "production",
      // No breadcrumbs: they'd record clicks, addresses and requests. Only what failed, and where.
      defaultIntegrations: false,
      integrations: [Sentry.globalHandlersIntegration(), Sentry.linkedErrorsIntegration(), Sentry.dedupeIntegration()],
      beforeSend: scrubEvent,
    });
    return Sentry;
  });
  return started;
}

/** Begins catching errors nothing else caught, once the page has finished loading. */
export function startErrorReporting() {
  if (!DSN) {
    return;
  }
  if (document.readyState === "complete") {
    void sentry();
  } else {
    window.addEventListener("load", () => void sentry(), { once: true });
  }
}

export function reportError(error: unknown) {
  if (DSN) {
    void sentry().then((Sentry) => Sentry.captureException(error));
  }
}
