import { describe, expect, it } from "vitest";
import { chosenTotals, EVERYTHING, isChosen, NOTHING, narrowed, toggled } from "./codSelection";

const found = { parcels: 46, amount: 100000 };

describe("a COD payout across pages", () => {
  it("starts with everything found, whichever page is showing", () => {
    expect(isChosen(EVERYTHING, "on-a-page-never-opened")).toBe(true);
    expect(chosenTotals(EVERYTHING, found)).toEqual({ parcels: 46, amount: 100000 });
  });

  it("takes off what's unticked, and puts it back", () => {
    const one = toggled(EVERYTHING, "a", 1500);
    expect(isChosen(one, "a")).toBe(false);
    expect(chosenTotals(one, found)).toEqual({ parcels: 45, amount: 98500 });
    expect(chosenTotals(toggled(one, "a", 1500), found)).toEqual(found);
  });

  it("counts only what's ticked after everything was unticked", () => {
    const one = toggled(toggled(NOTHING, "a", 1500), "b", 200);
    expect(isChosen(one, "a")).toBe(true);
    expect(isChosen(one, "c")).toBe(false);
    expect(chosenTotals(one, found)).toEqual({ parcels: 2, amount: 1700 });
  });

  it("stops counting a parcel someone else settled", () => {
    const left = toggled(toggled(EVERYTHING, "a", 1500), "b", 200);
    expect(
      chosenTotals(
        narrowed(left, (id) => id === "b"),
        { parcels: 45, amount: 98500 },
      ),
    ).toEqual({
      parcels: 44,
      amount: 98300,
    });
  });
});
