export interface PlanLimit {
  metric: string | null;
  limit: number | null;
}

let listener: (limit: PlanLimit) => void = () => {};

/** Called for any 402 plan.limit_reached, wherever it comes from. */
export function showPlanLimit(limit: PlanLimit) {
  listener(limit);
}

/** The dialog, once mounted, listens here. Apart from it so that every page needn't load a dialog. */
export function listenForPlanLimit(next: (limit: PlanLimit) => void) {
  listener = next;
  return () => {
    listener = () => {};
  };
}
