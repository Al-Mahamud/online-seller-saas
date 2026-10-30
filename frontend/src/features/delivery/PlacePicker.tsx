import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { areasQuery, districtsQuery, divisionsQuery, matchesPlace, type Named, usePlaceName } from "./geo";

export interface Group {
  name: string | null;
  places: Named[];
}

/** A list to choose one from, with a search box: quicker than three dropdowns on a phone. */
export function PickDialog({
  title,
  groups,
  onPick,
  onClose,
}: {
  title: string;
  groups: Group[];
  onPick: (place: Named) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const placeName = usePlaceName();
  const [search, setSearch] = useState("");
  const shown = groups
    .map((group) => ({ ...group, places: group.places.filter((place) => matchesPlace(place, search)) }))
    .filter((group) => group.places.length > 0);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex max-h-dvh flex-col">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={id}>{t("place.search")}</Label>
          <Input
            id={id}
            type="search"
            autoComplete="off"
            placeholder={t("place.searchHint")}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {shown.length === 0 && <p className="py-3 text-muted-foreground">{t("place.noMatch")}</p>}
          {shown.map((group) => (
            <div key={group.name ?? "all"}>
              {group.name && <p className="pt-3 text-sm font-semibold text-muted-foreground">{group.name}</p>}
              <ul>
                {group.places.map((place) => (
                  <li key={place.id}>
                    <button
                      type="button"
                      className="flex min-h-11 w-full cursor-pointer items-center border-b text-left"
                      onClick={() => onPick(place)}
                    >
                      {placeName(place)}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** The 64 districts under their divisions. */
export function useDistrictGroups(): { groups: Group[]; districts: Named[] } {
  const { t } = useTranslation();
  const placeName = usePlaceName();
  const { data: divisions } = useQuery(divisionsQuery);
  const { data: districts } = useQuery(districtsQuery);
  const groups = (divisions ?? []).map((division) => ({
    name: t("place.division", { name: placeName(division) }),
    places: (districts ?? []).filter((district) => district.divisionId === division.id),
  }));
  return { groups, districts: districts ?? [] };
}

export interface PlaceValue {
  districtId: number | null;
  areaId: number | null;
}

/** A field showing the choice, which opens the list. */
function PickField({
  label,
  shown,
  disabled,
  disabledText,
  onOpen,
  onClear,
}: {
  label: string;
  shown: string | null;
  disabled?: boolean;
  disabledText?: string;
  onOpen: () => void;
  onClear: () => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <span id={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </span>
      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          aria-labelledby={`${id} ${id}-value`}
          className="h-12 flex-1 justify-start font-normal disabled:text-muted-foreground disabled:opacity-100 md:h-11"
          disabled={disabled}
          onClick={onOpen}
        >
          <span id={`${id}-value`} className="truncate">
            {shown ?? (disabled ? disabledText : t("place.choose"))}
          </span>
        </Button>
        {shown && (
          <Button
            type="button"
            variant="outline"
            className="h-12 md:h-11"
            aria-label={`${t("place.clear")}: ${label}`}
            onClick={onClear}
          >
            {t("place.clear")}
          </Button>
        )}
      </div>
    </div>
  );
}

/** District, then its thana or upazila. Used for customers and order delivery addresses. */
export function PlacePicker({ value, onChange }: { value: PlaceValue; onChange: (value: PlaceValue) => void }) {
  const { t } = useTranslation();
  const placeName = usePlaceName();
  const [open, setOpen] = useState<"district" | "area">();
  const { groups, districts } = useDistrictGroups();
  const { data: areas } = useQuery({
    ...areasQuery(value.districtId ?? 0),
    enabled: value.districtId !== null,
  });
  const district = districts.find((place) => place.id === value.districtId);
  const area = areas?.find((place) => place.id === value.areaId);
  return (
    <div className="flex flex-col gap-4">
      <PickField
        label={t("place.district")}
        shown={district ? placeName(district) : null}
        onOpen={() => setOpen("district")}
        onClear={() => onChange({ districtId: null, areaId: null })}
      />
      <PickField
        label={t("place.area")}
        shown={area ? placeName(area) : null}
        disabled={value.districtId === null}
        disabledText={t("place.chooseDistrictFirst")}
        onOpen={() => setOpen("area")}
        onClear={() => onChange({ ...value, areaId: null })}
      />
      {open === "district" && (
        <PickDialog
          title={t("place.pickDistrict")}
          groups={groups}
          onClose={() => setOpen(undefined)}
          onPick={(place) => {
            onChange({ districtId: place.id, areaId: null });
            setOpen(undefined);
          }}
        />
      )}
      {open === "area" && (
        <PickDialog
          title={t("place.pickArea")}
          groups={[{ name: null, places: areas ?? [] }]}
          onClose={() => setOpen(undefined)}
          onPick={(place) => {
            onChange({ ...value, areaId: place.id });
            setOpen(undefined);
          }}
        />
      )}
    </div>
  );
}
