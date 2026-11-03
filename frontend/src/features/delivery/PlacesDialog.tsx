import { useQuery } from "@tanstack/react-query";
import { Search, X } from "lucide-react";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { areasQuery, matchesPlace, type Named, usePlaceName } from "./geo";
import { useDistrictGroups } from "./PlacePicker";

export type PlaceKind = "district" | "area";
export interface PickedPlace {
  kind: PlaceKind;
  place: Named;
}
export const placeKey = (kind: PlaceKind, id: number) => `${kind}:${id}`;

/**
 * Districts, or one district's thanas, to tick as many as wanted. A place is in one zone at most:
 * one already here shows ticked, and one in another zone says where, and moves here if picked.
 */
export default function PlacesDialog({
  zone,
  owners,
  onAdd,
  onClose,
}: {
  zone: string;
  /** Which zone each place is in now, by {@link placeKey}; `null` for this one. */
  owners: Map<string, string | null>;
  onAdd: (places: PickedPlace[]) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const placeName = usePlaceName();
  const { groups, districts } = useDistrictGroups();
  const [kind, setKind] = useState<PlaceKind>("district");
  const [districtId, setDistrictId] = useState<number>();
  const [search, setSearch] = useState("");
  const [picked, setPicked] = useState<PickedPlace[]>([]);
  const { data: areas } = useQuery({ ...areasQuery(districtId ?? 0), enabled: districtId !== undefined });
  const shown = (kind === "district" ? groups : districtId === undefined ? [] : [{ name: null, places: areas ?? [] }])
    .map((group) => ({ ...group, places: group.places.filter((place) => matchesPlace(place, search)) }))
    .filter((group) => group.places.length > 0);
  const isPicked = (place: Named) => picked.some((item) => item.kind === kind && item.place.id === place.id);
  const toggle = (place: Named) =>
    setPicked((current) =>
      isPicked(place)
        ? current.filter((item) => !(item.kind === kind && item.place.id === place.id))
        : [...current, { kind, place }],
    );
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="flex max-h-[calc(100dvh-2rem)] flex-col gap-0 overflow-hidden p-0 max-md:h-dvh max-md:max-h-dvh max-md:max-w-full max-md:rounded-none sm:max-w-[520px]"
      >
        <header className="relative flex flex-none items-start justify-between gap-2.5 bg-ledger py-3.5 pr-3.5 pl-10 text-white">
          <div aria-hidden="true" className="absolute inset-y-0 left-5 w-0.5 bg-marigold" />
          <div className="min-w-0">
            <DialogTitle className="text-[22px] font-bold">{t("delivery.page.pickTitle")}</DialogTitle>
            <DialogDescription className="text-sm text-ledger-text">
              {t("delivery.page.pickHint", { zone })}
            </DialogDescription>
          </div>
          <button
            type="button"
            aria-label={t("page.close")}
            className="flex size-11 flex-none cursor-pointer items-center justify-center rounded-[10px] hover:bg-white/10"
            onClick={onClose}
          >
            <X aria-hidden="true" className="size-5" />
          </button>
        </header>
        <div className="flex flex-none flex-col gap-2.5 border-b px-4 py-3">
          {/* biome-ignore lint/a11y/useSemanticElements: two buttons that belong together, with no form around them. */}
          <div
            role="group"
            aria-label={t("delivery.page.pickWhat")}
            className="grid grid-cols-2 gap-1.5 rounded-xl bg-[#E9ECE8] p-1"
          >
            {(["district", "area"] as const).map((option) => (
              <button
                key={option}
                type="button"
                aria-pressed={kind === option}
                className={cn(
                  "h-10 cursor-pointer rounded-[9px] text-[15px] font-bold",
                  kind === option && "bg-card shadow-[0_2px_6px_rgba(7,26,20,0.12)]",
                )}
                onClick={() => {
                  setKind(option);
                  setSearch("");
                }}
              >
                {t(option === "district" ? "delivery.page.pickDistricts" : "delivery.page.pickAreas")}
              </button>
            ))}
          </div>
          {kind === "area" && (
            <div className="flex flex-col gap-1">
              <label htmlFor={`${id}-district`} className="text-sm font-semibold">
                {t("delivery.page.pickInDistrict")}
              </label>
              <select
                id={`${id}-district`}
                className="h-11 rounded-[10px] border-[1.5px] border-input bg-card px-2 text-[15px]"
                value={districtId ?? ""}
                onChange={(event) => setDistrictId(event.target.value ? Number(event.target.value) : undefined)}
              >
                <option value="">{t("delivery.page.anyDistrict")}</option>
                {groups.map((group) => (
                  <optgroup key={group.name ?? ""} label={group.name ?? ""}>
                    {group.places.map((place) => (
                      <option key={place.id} value={place.id}>
                        {placeName(place)}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
            </div>
          )}
          <div className="relative">
            <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-primary" />
            <input
              type="search"
              aria-label={t("delivery.page.pickSearch")}
              placeholder={t("place.searchHint")}
              autoComplete="off"
              className="h-11 w-full rounded-[10px] border-[1.5px] border-input bg-card pr-3 pl-9.5 text-base"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-2 pt-1 pb-2">
          {(kind === "district" ? districts.length > 0 : districtId !== undefined && areas) && shown.length === 0 && (
            <p className="p-3 text-muted-foreground">{t("place.noMatch")}</p>
          )}
          {shown.map((group) => (
            <fieldset key={group.name ?? "all"}>
              {group.name && (
                <legend className="px-2 pt-2.5 pb-1 text-[13px] font-bold text-muted-foreground">{group.name}</legend>
              )}
              {group.places.map((place) => {
                const owner = owners.get(placeKey(kind, place.id));
                const here = owner === null;
                const on = here || isPicked(place);
                return (
                  <label
                    key={place.id}
                    className={cn(
                      "flex min-h-12 cursor-pointer items-center gap-2.5 rounded-[10px] px-2 py-1 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                      isPicked(place) ? "bg-[#F3F9F6]" : "hover:bg-muted",
                      here && "cursor-default hover:bg-transparent",
                    )}
                  >
                    <input
                      type="checkbox"
                      checked={on}
                      disabled={here}
                      className="size-5 flex-none accent-primary"
                      onChange={() => toggle(place)}
                    />
                    <span className="min-w-0 flex-1">
                      <span className={cn("block text-[15px] font-bold", here && "text-muted-foreground")}>
                        {placeName(place)}
                      </span>
                      {here && (
                        <span className="block text-[13px] text-muted-foreground">{t("delivery.page.here")}</span>
                      )}
                      {owner && (
                        <span className="block text-[13px] text-warning-foreground">
                          {t("delivery.page.elsewhere", { zone: owner })}
                        </span>
                      )}
                    </span>
                  </label>
                );
              })}
            </fieldset>
          ))}
        </div>
        <footer className="flex flex-none flex-wrap items-center justify-between gap-2.5 border-t bg-background px-4 pt-3 pb-3.5">
          <p role="status" className="text-[15px] font-semibold text-[#3E4842]">
            {picked.length
              ? t("delivery.page.picked", { count: format.number(picked.length) })
              : t("delivery.page.nonePicked")}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              className="h-11 cursor-pointer rounded-xl border-[1.5px] border-input bg-card px-4 text-[15px] font-bold hover:bg-muted"
              onClick={onClose}
            >
              {t("delivery.page.cancel")}
            </button>
            <button
              type="button"
              disabled={picked.length === 0}
              className="h-11 cursor-pointer rounded-xl bg-primary px-4.5 text-[15px] font-bold text-primary-foreground hover:bg-[#0a5c44] disabled:cursor-default disabled:bg-[#9AA19C]"
              onClick={() => onAdd(picked)}
            >
              {picked.length
                ? t("delivery.page.addPicked", { count: format.number(picked.length) })
                : t("delivery.page.add")}
            </button>
          </div>
        </footer>
      </DialogContent>
    </Dialog>
  );
}
