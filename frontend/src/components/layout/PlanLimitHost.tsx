import { lazy, Suspense, useEffect, useState } from "react";
import { listenForPlanLimit, type PlanLimit } from "./planLimit";

// Rarely shown, and it brings the dialog library with it.
const PlanLimitDialog = lazy(() => import("./PlanLimitDialog"));

/** Mounted once, in the app's frame: it listens all the time, and loads the dialog when there's something to say. */
export function PlanLimitHost() {
  const [limit, setLimit] = useState<PlanLimit>();
  useEffect(() => listenForPlanLimit(setLimit), []);
  return limit ? (
    <Suspense fallback={null}>
      <PlanLimitDialog limit={limit} onClose={() => setLimit(undefined)} />
    </Suspense>
  ) : null;
}
