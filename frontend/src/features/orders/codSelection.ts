/**
 * Which of a courier's parcels a payout covers, across every page of the list. Only one page is ever
 * loaded, so this holds the exceptions, each with its cash, and the totals come from the server's.
 */
export interface CodSelection {
  /** True: everything found, less `ids`. False: only `ids`. */
  allExcept: boolean;
  /** Parcel id to the cash collected on it, in poisha. */
  ids: ReadonlyMap<string, number>;
}

/** Usually a payout covers all of it, so everything starts ticked. */
export const EVERYTHING: CodSelection = { allExcept: true, ids: new Map() };
export const NOTHING: CodSelection = { allExcept: false, ids: new Map() };

export function isChosen(selection: CodSelection, id: string): boolean {
  return selection.ids.has(id) !== selection.allExcept;
}

export function toggled(selection: CodSelection, id: string, cash: number): CodSelection {
  const ids = new Map(selection.ids);
  if (!ids.delete(id)) {
    ids.set(id, cash);
  }
  return { ...selection, ids };
}

/** What the payout comes to, given what the server says the search found. */
export function chosenTotals(selection: CodSelection, found: { parcels: number; amount: number }) {
  const cash = [...selection.ids.values()].reduce((sum, one) => sum + one, 0);
  return selection.allExcept
    ? { parcels: found.parcels - selection.ids.size, amount: found.amount - cash }
    : { parcels: selection.ids.size, amount: cash };
}

/** The exceptions that still count, after parcels were settled by someone else meanwhile. */
export function narrowed(selection: CodSelection, keep: (id: string) => boolean): CodSelection {
  return { ...selection, ids: new Map([...selection.ids].filter(([id]) => keep(id))) };
}
