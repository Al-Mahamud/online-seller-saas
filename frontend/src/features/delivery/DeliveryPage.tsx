import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { X } from "lucide-react";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import type { components } from "@/api/schema";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseTaka, takaInput } from "@/lib/money";
import { areasQuery, type Named, usePlaceName } from "./geo";
import { PickDialog, PlacePicker, type PlaceValue, useDistrictGroups } from "./PlacePicker";

type Zone = components["schemas"]["DeliveryZonesZone"];

const zonesQuery = queryOptions({
  queryKey: ["delivery-zones"],
  queryFn: () => call(api.GET("/api/v1/delivery-zones")),
});

interface Draft {
  /** For the list only: a new zone has no id yet. */
  key: string;
  id?: string;
  name: string;
  charge: string;
  isDefault: boolean;
  districts: Named[];
  areas: Named[];
}

function drafts(zones: Zone[]): Draft[] {
  return zones.map((zone) => ({
    key: zone.id,
    id: zone.id,
    name: zone.name,
    charge: takaInput(zone.charge),
    isDefault: zone.isDefault,
    districts: zone.districts,
    areas: zone.areas,
  }));
}

/** All the zones are edited together and saved as one, since a place may move between them. */
export function DeliveryPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: zones } = useQuery(zonesQuery);
  const [edited, setEdited] = useState<Draft[]>();
  const [problem, setProblem] = useState<string>();
  useEffect(() => {
    if (zones) {
      setEdited(drafts(zones));
    }
  }, [zones]);
  const save = useMutation({
    mutationFn: (body: components["schemas"]["DeliveryZoneZonesRequest"]) =>
      call(api.PUT("/api/v1/delivery-zones", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      queryClient.setQueryData(zonesQuery.queryKey, saved);
      void queryClient.invalidateQueries({ queryKey: ["delivery-quote"] });
      toast.success(t("delivery.saved"));
    },
    onError: (error) => setProblem(errorText(error)),
  });

  function submit() {
    if (!edited) {
      return;
    }
    const body = [];
    for (const zone of edited) {
      const charge = parseTaka(zone.charge);
      if (charge === null) {
        setProblem(t("delivery.badCharge", { name: zone.name }));
        return;
      }
      body.push({
        id: zone.id,
        name: zone.name.trim(),
        charge,
        isDefault: zone.isDefault,
        districtIds: zone.districts.map((place) => place.id),
        areaIds: zone.areas.map((place) => place.id),
      });
    }
    setProblem(undefined);
    save.mutate({ zones: body });
  }

  const update = (key: string, change: Partial<Draft>) =>
    setEdited((all) => all?.map((zone) => (zone.key === key ? { ...zone, ...change } : zone)));
  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <div>
        <BackToMore />
        <PageHeader title={t("delivery.title")} />
        <p className="text-muted-foreground">{t("delivery.explain")}</p>
      </div>
      {edited?.map((zone, index) => (
        <ZoneCard
          key={zone.key}
          zone={zone}
          number={index + 1}
          onChange={(change) => update(zone.key, change)}
          onMakeDefault={() =>
            setEdited((all) => all?.map((other) => ({ ...other, isDefault: other.key === zone.key })))
          }
          onRemove={() => setEdited((all) => all?.filter((other) => other.key !== zone.key))}
        />
      ))}
      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        <Button disabled={save.isPending || !edited} onClick={submit}>
          {t("delivery.save")}
        </Button>
        <Button
          variant="outline"
          onClick={() =>
            setEdited((all) => [
              ...(all ?? []),
              { key: crypto.randomUUID(), name: "", charge: "", isDefault: false, districts: [], areas: [] },
            ])
          }
        >
          {t("delivery.addZone")}
        </Button>
      </div>
      <QuoteCheck />
    </div>
  );
}

function ZoneCard({
  zone,
  number,
  onChange,
  onMakeDefault,
  onRemove,
}: {
  zone: Draft;
  number: number;
  onChange: (change: Partial<Draft>) => void;
  onMakeDefault: () => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const placeName = usePlaceName();
  const { groups } = useDistrictGroups();
  const [adding, setAdding] = useState<"district" | "area-district" | { districtId: number }>();
  const pickingArea = typeof adding === "object" ? adding.districtId : null;
  const { data: areas } = useQuery({ ...areasQuery(pickingArea ?? 0), enabled: pickingArea !== null });
  const addTo = (field: "districts" | "areas", place: Named) => {
    if (!zone[field].some((old) => old.id === place.id)) {
      onChange({ [field]: [...zone[field], place] });
    }
    setAdding(undefined);
  };
  return (
    <Section title={zone.name || t("delivery.zone", { number: format.number(number) })}>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-name`} className="text-[15px] leading-normal font-semibold">
          {t("delivery.zoneName")}
        </Label>
        <Input
          id={`${id}-name`}
          value={zone.name}
          maxLength={200}
          onChange={(event) => onChange({ name: event.target.value })}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-charge`} className="text-[15px] leading-normal font-semibold">
          {t("delivery.charge")}
        </Label>
        <Input
          id={`${id}-charge`}
          inputMode="decimal"
          value={zone.charge}
          onChange={(event) => onChange({ charge: event.target.value })}
        />
      </div>
      <Label className="min-h-11 text-base font-normal">
        <input
          type="radio"
          name="default-zone"
          className="size-5 accent-primary"
          checked={zone.isDefault}
          onChange={onMakeDefault}
        />
        {t("delivery.default")}
      </Label>
      {!zone.isDefault && (
        <>
          <PlaceList
            title={t("delivery.districts")}
            places={zone.districts}
            name={placeName}
            onRemove={(place) => onChange({ districts: zone.districts.filter((old) => old.id !== place.id) })}
          />
          <PlaceList
            title={t("delivery.areas")}
            places={zone.areas}
            name={placeName}
            onRemove={(place) => onChange({ areas: zone.areas.filter((old) => old.id !== place.id) })}
          />
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => setAdding("district")}>
              {t("delivery.addDistrict")}
            </Button>
            <Button variant="outline" onClick={() => setAdding("area-district")}>
              {t("delivery.addArea")}
            </Button>
            <Button variant="outline" onClick={onRemove}>
              {t("delivery.removeZone")}
            </Button>
          </div>
        </>
      )}
      {adding === "district" && (
        <PickDialog
          title={t("delivery.addDistrict")}
          groups={groups}
          onClose={() => setAdding(undefined)}
          onPick={(place) => addTo("districts", place)}
        />
      )}
      {adding === "area-district" && (
        <PickDialog
          title={t("place.pickDistrict")}
          groups={groups}
          onClose={() => setAdding(undefined)}
          onPick={(place) => setAdding({ districtId: place.id })}
        />
      )}
      {pickingArea !== null && areas && (
        <PickDialog
          title={t("place.pickArea")}
          groups={[{ name: null, places: areas }]}
          onClose={() => setAdding(undefined)}
          onPick={(place) => addTo("areas", place)}
        />
      )}
    </Section>
  );
}

function PlaceList({
  title,
  places,
  name,
  onRemove,
}: {
  title: string;
  places: Named[];
  name: (place: Named) => string;
  onRemove: (place: Named) => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-[15px] font-semibold">{title}</p>
      {places.length === 0 && <p className="text-sm text-muted-foreground">{t("delivery.noPlaces")}</p>}
      {places.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {places.map((place) => (
            <li key={place.id} className="flex items-center gap-1 rounded-lg border border-input bg-card pl-3">
              {name(place)}
              <button
                type="button"
                aria-label={t("delivery.removePlace", { name: name(place) })}
                className="flex size-9 cursor-pointer items-center justify-center text-muted-foreground"
                onClick={() => onRemove(place)}
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** What an order to a place would be charged, by the saved zones. */
function QuoteCheck() {
  const { t } = useTranslation();
  const format = useFormat();
  const [place, setPlace] = useState<PlaceValue>({ districtId: null, areaId: null });
  const { data: quote } = useQuery({
    queryKey: ["delivery-quote", place],
    queryFn: () =>
      call(
        api.GET("/api/v1/delivery-zones/quote", {
          params: { query: { districtId: place.districtId ?? undefined, areaId: place.areaId ?? undefined } },
        }),
      ),
  });
  return (
    <Section title={t("delivery.check")}>
      <p className="text-sm text-muted-foreground">{t("delivery.checkHint")}</p>
      <PlacePicker value={place} onChange={setPlace} />
      {quote && (
        <p role="status" className="text-lg font-semibold">
          {t("delivery.quote", { zone: quote.zoneName, charge: format.taka(quote.charge) })}
        </p>
      )}
    </Section>
  );
}
