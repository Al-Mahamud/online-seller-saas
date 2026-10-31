import { useSyncExternalStore } from "react";

/** What Chrome on Android offers when the app can be added to the home screen. */
interface InstallPrompt extends Event {
  prompt: () => Promise<void>;
}

let offer: InstallPrompt | null = null;
const listeners = new Set<() => void>();

function changed(next: InstallPrompt | null) {
  offer = next;
  for (const listener of listeners) {
    listener();
  }
}

window.addEventListener("beforeinstallprompt", (event) => {
  // Kept for the seller's own tap, instead of the browser's banner over whatever they're doing.
  event.preventDefault();
  changed(event as InstallPrompt);
});
window.addEventListener("appinstalled", () => changed(null));

/** Asks to add the app to the home screen, or null when this browser isn't offering that now. */
export function useInstallApp(): (() => void) | null {
  const current = useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => offer,
  );
  return current
    ? () => {
        void current.prompt();
        // An offer can be used once.
        changed(null);
      }
    : null;
}
