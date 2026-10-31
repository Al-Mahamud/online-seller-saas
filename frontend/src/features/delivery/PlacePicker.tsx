import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { areasQuery, districtsQuery, divisionsQuery, type Named, usePlaceName } from "./geo";
import type { Group } from "./PickDialog";

// Only when a list is opened: it brings the dialog library, which the order form otherwise doesn't need.
const PickDialog = lazy(() => import("./PickDialog"));

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
  error,
  onOpen,
  onClear,
}: {
  label: string;
  shown: string | null;
  disabled?: boolean;
  disabledText?: string;
  error?: string;
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
      {error && (
        <p data-problem tabIndex={-1} className="text-sm text-destructive outline-none">
          {error}
        </p>
      )}
    </div>
  );
}

/** District, then its thana or upazila. Used for customers and order delivery addresses. */
export function PlacePicker({
  value,
  onChange,
  districtLabel,
  districtError,
}: {
  value: PlaceValue;
  onChange: (value: PlaceValue) => void;
  /** Where the district is needed, as on an order, the label stops saying it's optional. */
  districtLabel?: string;
  districtError?: string;
}) {
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
        label={districtLabel ?? t("place.district")}
        shown={district ? placeName(district) : null}
        error={districtError}
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
      <Suspense fallback={null}>
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
      </Suspense>
    </div>
  );
}
