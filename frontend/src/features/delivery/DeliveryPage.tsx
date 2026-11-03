import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Globe, Minus, Plus, Trash2, X } from "lucide-react";
import { lazy, Suspense, useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import type { components } from "@/api/schema";
import { BackToMore } from "@/components/layout/PageHeader";
import { useFormat } from "@/lib/format";
import { parseTaka, takaInput } from "@/lib/money";
import { cn } from "@/lib/utils";
import { areasQuery, type Named, usePlaceName } from "./geo";
import { useDistrictGroups } from "./PlacePicker";
import { type PickedPlace, placeKey } from "./PlacesDialog";

// With its lists of districts, only needed when adding places.
const PlacesDialog = lazy(() => import("./PlacesDialog"));

type Zone = components["schemas"]["DeliveryZonesZone"];

const zonesQuery = queryOptions({
  queryKey: ["delivery-zones"],
  queryFn: () => call(api.GET("/api/v1/delivery-zones")),
});

interface Moved {
  key: string;
  place: Named;
  from: string;
  /** The zone it came from, to put it back. */
  fromKey: string;
}

interface Draft {
  /** For the list only: a new zone has no id yet. */
  key: string;
  id?: string;
  name: string;
  charge: string;
  isDefault: boolean;
  districts: Named[];
  areas: Named[];
  /** Places picked here from another zone, said under the card until saved. */
  moved: Moved[];
}

const STEP = 1000;
const TINTS = [
  { chip: "bg-accent text-accent-foreground", swatch: "bg-primary" },
  { chip: "bg-[#E8EEF9] text-[#24427A]", swatch: "bg-[#3B63B0]" },
  { chip: "bg-[#F6E9F2] text-[#7A2D5E]", swatch: "bg-[#A64B86]" },
  { chip: "bg-muted text-[#3E4842]", swatch: "bg-[#6B746E]" },
];
const tintOf = (index: number) => TINTS[index % TINTS.length] as (typeof TINTS)[number];
const STEPPER =
  "flex h-11 w-10 flex-none cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card hover:bg-muted";
const FIELD = "h-11 rounded-[10px] border-[1.5px] bg-card px-3 text-base font-bold";

function drafts(zones: Zone[]): Draft[] {
  return zones.map((zone) => ({
    key: zone.id,
    id: zone.id,
    name: zone.name,
    charge: takaInput(zone.charge),
    isDefault: zone.isDefault,
    districts: zone.districts,
    areas: zone.areas,
    moved: [],
  }));
}

/** What saving would send, to tell whether anything changed. */
const shape = (zones: Draft[]) =>
  JSON.stringify(
    zones.map((zone) => [
      zone.id,
      zone.name.trim(),
      parseTaka(zone.charge),
      zone.isDefault,
      zone.districts.map((place) => place.id).sort(),
      zone.areas.map((place) => place.id).sort(),
    ]),
  );

interface Problems {
  name?: "delivery.page.nameNeeded" | "delivery.page.nameTwice";
  charge?: "delivery.page.badCharge";
}

/** The fields to fix before saving, by zone. */
function problemsOf(zones: Draft[]): Map<string, Problems> {
  const problems = new Map<string, Problems>();
  const seen = new Set<string>();
  for (const zone of zones) {
    const found: Problems = {};
    const name = zone.name.trim().toLowerCase();
    if (!name) {
      found.name = "delivery.page.nameNeeded";
    } else if (seen.has(name)) {
      found.name = "delivery.page.nameTwice";
    }
    seen.add(name);
    if (parseTaka(zone.charge) === null) {
      found.charge = "delivery.page.badCharge";
    }
    if (found.name || found.charge) {
      problems.set(zone.key, found);
    }
  }
  return problems;
}

/**
 * The delivery zones, edited together and saved as one, since a place may move between them; the
 * zone for everywhere else stays last. Beside them, what a place would be charged by the saved rules.
 */
export function DeliveryPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const queryClient = useQueryClient();
  const { data: zones } = useQuery(zonesQuery);
  const [edited, setEdited] = useState<Draft[]>();
  const [checked, setChecked] = useState(false);
  const [problem, setProblem] = useState<string>();
  useEffect(() => {
    if (zones) {
      setEdited(drafts(zones));
      setChecked(false);
    }
  }, [zones]);
  const dirty = !!zones && !!edited && shape(drafts(zones)) !== shape(edited);
  const problems = edited ? problemsOf(edited) : new Map<string, Problems>();
  const shownProblems = checked ? problems : new Map<string, Problems>();
  const fieldCount = [...shownProblems.values()].reduce(
    (sum, found) => sum + (found.name ? 1 : 0) + (found.charge ? 1 : 0),
    0,
  );
  const save = useMutation({
    mutationFn: (body: components["schemas"]["DeliveryZoneZonesRequest"]) =>
      call(api.PUT("/api/v1/delivery-zones", { body })),
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      queryClient.setQueryData(zonesQuery.queryKey, saved);
      void queryClient.invalidateQueries({ queryKey: ["delivery-quote"] });
      setProblem(undefined);
      toast.success(t("delivery.saved"));
    },
    onError: (error) => setProblem(errorText(error)),
  });

  function submit() {
    if (!edited) {
      return;
    }
    setChecked(true);
    if (problems.size > 0) {
      return;
    }
    setProblem(undefined);
    save.mutate({
      zones: edited.map((zone) => ({
        id: zone.id,
        name: zone.name.trim(),
        charge: parseTaka(zone.charge) ?? 0,
        isDefault: zone.isDefault,
        districtIds: zone.districts.map((place) => place.id),
        areaIds: zone.areas.map((place) => place.id),
      })),
    });
  }

  const update = (key: string, change: Partial<Draft>) =>
    setEdited((all) => all?.map((zone) => (zone.key === key ? { ...zone, ...change } : zone)));
  const placed = (edited ?? []).filter((zone) => !zone.isDefault);
  const rest = edited?.find((zone) => zone.isDefault);

  /** Puts places in a zone, taking each out of whichever zone had it. */
  function addPlaces(target: Draft, places: PickedPlace[]) {
    setEdited((all) => {
      if (!all) {
        return all;
      }
      const keys = new Set(places.map((item) => placeKey(item.kind, item.place.id)));
      const moved: Moved[] = [];
      const taken = all.map((zone) => {
        if (zone.key === target.key) {
          return zone;
        }
        const keep = (kind: "district" | "area") => (place: Named) => {
          if (!keys.has(placeKey(kind, place.id))) {
            return true;
          }
          moved.push({ key: placeKey(kind, place.id), place, from: zone.name, fromKey: zone.key });
          return false;
        };
        return { ...zone, districts: zone.districts.filter(keep("district")), areas: zone.areas.filter(keep("area")) };
      });
      return taken.map((zone) => {
        if (zone.key !== target.key) {
          return zone;
        }
        const add = (kind: "district" | "area", list: Named[]) => [
          ...list,
          ...places
            .filter((item) => item.kind === kind && !list.some((place) => place.id === item.place.id))
            .map((item) => item.place),
        ];
        return {
          ...zone,
          districts: add("district", zone.districts),
          areas: add("area", zone.areas),
          moved: [...zone.moved.filter((note) => !keys.has(note.key)), ...moved],
        };
      });
    });
  }

  /** Undoes a move: the place goes back to the zone it came from. */
  function moveBack(target: Draft, note: Moved) {
    setEdited((all) => {
      const zone = all?.find((other) => other.key === target.key);
      const [kind, id] = note.key.split(":") as ["district" | "area", string];
      const list = kind === "district" ? zone?.districts : zone?.areas;
      const place = list?.find((item) => item.id === Number(id));
      if (!all || !place) {
        return all;
      }
      return all.map((other) => {
        const field = kind === "district" ? "districts" : "areas";
        if (other.key === target.key) {
          return {
            ...other,
            [field]: other[field].filter((item) => item.id !== place.id),
            moved: other.moved.filter((item) => item.key !== note.key),
          };
        }
        if (other.key === note.fromKey) {
          return { ...other, [field]: [...other[field], place] };
        }
        return other;
      });
    });
  }

  return (
    <div className="flex flex-col gap-4">
      <header className="relative flex flex-col gap-3.5 bg-ledger pt-1 pr-4.5 pb-10.5 pl-6.5 text-white max-md:-mx-4 max-md:-mt-4 md:overflow-hidden md:rounded-2xl md:pt-4.5 md:pr-7 md:pb-5 md:pl-14">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
        <div className="[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("delivery.title")}</h1>
          <p className="text-[15px] text-ledger-text">{t("delivery.page.subtitle")}</p>
        </div>
        <ol aria-label={t("delivery.page.how")} className="grid gap-2 md:grid-cols-3">
          {(["1", "2", "3"] as const).map((step, index) => (
            <li key={step} className="flex items-start gap-2.5 rounded-xl bg-white/8 px-3 py-2.5">
              <span
                aria-hidden="true"
                className="flex size-6.5 flex-none items-center justify-center rounded-full bg-marigold text-sm font-bold text-ledger"
              >
                {["১", "২", "৩"][index]}
              </span>
              <span className="text-sm leading-snug">
                <strong className="block text-[15px]">{t(`delivery.page.step${step}`)}</strong>
                <span className="text-ledger-text">{t(`delivery.page.step${step}Hint`)}</span>
              </span>
            </li>
          ))}
        </ol>
      </header>
      <div className="grid items-start gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-11.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3 max-md:pt-3.5 md:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="flex min-w-0 flex-col gap-3">
          {placed.map((zone, index) => (
            <ZoneCard
              key={zone.key}
              zone={zone}
              index={index}
              zones={edited ?? []}
              problems={shownProblems.get(zone.key)}
              onChange={(change) => update(zone.key, change)}
              onRemove={() => setEdited((all) => all?.filter((other) => other.key !== zone.key))}
              onAdd={(places) => addPlaces(zone, places)}
              onMoveBack={(note) => moveBack(zone, note)}
            />
          ))}
          <button
            type="button"
            className="flex min-h-13 cursor-pointer items-center justify-center gap-2 rounded-2xl border-[1.5px] border-dashed border-[#9FC7B6] bg-card text-[15px] font-bold text-primary hover:bg-accent"
            onClick={() =>
              setEdited((all) => {
                const zonesNow = all ?? [];
                const fresh: Draft = {
                  key: crypto.randomUUID(),
                  name: "",
                  charge: "",
                  isDefault: false,
                  districts: [],
                  areas: [],
                  moved: [],
                };
                // New ones go above the zone for everywhere else.
                const restAt = zonesNow.findIndex((zone) => zone.isDefault);
                return restAt < 0
                  ? [...zonesNow, fresh]
                  : [...zonesNow.slice(0, restAt), fresh, ...zonesNow.slice(restAt)];
              })
            }
          >
            <Plus aria-hidden="true" className="size-4" strokeWidth={2.6} />
            {t("delivery.page.addZone")}
          </button>
          {rest && (
            <RestCard
              zone={rest}
              problems={shownProblems.get(rest.key)}
              onChange={(change) => update(rest.key, change)}
            />
          )}
          {(dirty || problem) && (
            <section
              aria-label={t("delivery.page.unsavedRegion")}
              className="sticky bottom-[calc(96px+env(safe-area-inset-bottom))] z-10 flex flex-wrap items-center justify-between gap-2.5 rounded-2xl bg-ledger py-3 pr-3.5 pl-4.5 text-white shadow-[0_16px_36px_rgba(7,26,20,0.35)] md:bottom-6"
            >
              <p
                role={problem || fieldCount ? "alert" : "status"}
                className="flex min-w-0 items-center gap-2 text-[15px] font-bold"
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "size-2 flex-none rounded-full",
                    problem || fieldCount ? "bg-[#F7A99F]" : "bg-marigold",
                  )}
                />
                {problem ??
                  (fieldCount
                    ? t("delivery.page.fix", { count: format.number(fieldCount) })
                    : t("delivery.page.unsaved"))}
              </p>
              <div className="flex gap-2 max-md:w-full">
                <button
                  type="button"
                  className="h-11 flex-1 cursor-pointer rounded-xl border-[1.5px] border-[#5E8B7C] px-4 text-[15px] font-bold whitespace-nowrap hover:bg-white/10"
                  onClick={() => {
                    if (zones) {
                      setEdited(drafts(zones));
                    }
                    setChecked(false);
                    setProblem(undefined);
                  }}
                >
                  {t("delivery.page.discard")}
                </button>
                <button
                  type="button"
                  disabled={save.isPending}
                  className="h-11 flex-1 cursor-pointer rounded-xl bg-marigold px-4.5 text-[15px] font-bold whitespace-nowrap text-ledger hover:bg-marigold-text disabled:opacity-70"
                  onClick={submit}
                >
                  {t("delivery.page.save")}
                </button>
              </div>
            </section>
          )}
        </div>
        <QuoteCheck zones={zones ?? []} dirty={dirty} />
      </div>
    </div>
  );
}

function Charge({
  id,
  name,
  value,
  error,
  onChange,
}: {
  id: string;
  name: string;
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  const step = (by: number) => onChange(takaInput(Math.max(0, (parseTaka(value) ?? 0) + by)));
  return (
    <div className="flex flex-none flex-col gap-1">
      <label htmlFor={id} className="text-[13px] font-semibold text-muted-foreground">
        {t("delivery.page.charge")}
      </label>
      <div className="flex items-center gap-1">
        <button
          type="button"
          aria-label={t("delivery.page.less", { name })}
          className={STEPPER}
          onClick={() => step(-STEP)}
        >
          <Minus aria-hidden="true" className="size-4" />
        </button>
        <div
          className={cn(
            "flex h-11 items-center rounded-[10px] border-[1.5px] bg-card px-2.5 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
            error ? "border-destructive" : "border-input",
          )}
        >
          <span aria-hidden="true" className="text-[17px] font-bold text-muted-foreground">
            ৳
          </span>
          <input
            id={id}
            inputMode="decimal"
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-error` : undefined}
            className="w-14 bg-transparent text-center text-lg font-bold outline-none"
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
        </div>
        <button
          type="button"
          aria-label={t("delivery.page.more", { name })}
          className={STEPPER}
          onClick={() => step(STEP)}
        >
          <Plus aria-hidden="true" className="size-4" />
        </button>
      </div>
    </div>
  );
}

function ZoneCard({
  zone,
  index,
  zones,
  problems,
  onChange,
  onRemove,
  onAdd,
  onMoveBack,
}: {
  zone: Draft;
  index: number;
  zones: Draft[];
  problems?: Problems;
  onChange: (change: Partial<Draft>) => void;
  onRemove: () => void;
  onAdd: (places: PickedPlace[]) => void;
  onMoveBack: (note: Moved) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const placeName = usePlaceName();
  const [adding, setAdding] = useState(false);
  const name = zone.name.trim() || t("delivery.page.unnamed");
  const tint = tintOf(index);
  const parts = [
    zone.districts.length ? t("delivery.page.districtCount", { count: format.number(zone.districts.length) }) : "",
    zone.areas.length ? t("delivery.page.areaCount", { count: format.number(zone.areas.length) }) : "",
  ].filter(Boolean);
  const owners = new Map<string, string | null>();
  for (const other of zones) {
    const owner = other.key === zone.key ? null : other.name.trim() || t("delivery.page.unnamed");
    for (const place of other.districts) owners.set(placeKey("district", place.id), owner);
    for (const place of other.areas) owners.set(placeKey("area", place.id), owner);
  }
  const chips = [
    ...zone.districts.map((place) => ({ kind: "district" as const, place })),
    ...zone.areas.map((place) => ({ kind: "area" as const, place })),
  ];
  const remove = (kind: "district" | "area", place: Named) =>
    onChange(
      kind === "district"
        ? { districts: zone.districts.filter((old) => old.id !== place.id) }
        : { areas: zone.areas.filter((old) => old.id !== place.id) },
    );
  return (
    <section aria-label={name} className="flex flex-col gap-3 rounded-2xl border bg-card p-3.5 md:p-4.5">
      <div className="flex flex-wrap items-end gap-2.5">
        <span
          aria-hidden="true"
          className={cn(
            "flex size-8 flex-none items-center justify-center self-center rounded-[10px] text-[15px] font-bold",
            tint.chip,
          )}
        >
          {format.number(index + 1)}
        </span>
        <div className="flex min-w-0 flex-[1_1_180px] flex-col gap-1">
          <label htmlFor={`${id}-name`} className="text-[13px] font-semibold text-muted-foreground">
            {t("delivery.page.zoneName")}
          </label>
          <input
            id={`${id}-name`}
            maxLength={50}
            aria-invalid={problems?.name ? true : undefined}
            aria-describedby={problems?.name ? `${id}-name-error` : undefined}
            className={cn(FIELD, problems?.name ? "border-destructive" : "border-input")}
            value={zone.name}
            onChange={(event) => onChange({ name: event.target.value })}
          />
        </div>
        <Charge
          id={`${id}-charge`}
          name={name}
          value={zone.charge}
          error={problems?.charge}
          onChange={(charge) => onChange({ charge })}
        />
        <button
          type="button"
          aria-label={t("delivery.page.remove", { name })}
          className="flex size-11 flex-none cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card text-destructive hover:bg-danger-soft"
          onClick={onRemove}
        >
          <Trash2 aria-hidden="true" className="size-4.5" />
        </button>
      </div>
      {problems?.name && (
        <p id={`${id}-name-error`} className="text-sm text-destructive">
          {t(problems.name)}
        </p>
      )}
      {problems?.charge && (
        <p id={`${id}-charge-error`} className="text-sm text-destructive">
          {t(problems.charge)}
        </p>
      )}
      <div className="flex flex-col gap-2 rounded-xl bg-background p-3">
        <p className="flex justify-between gap-2 text-sm font-bold">
          <span>{t("delivery.page.where")}</span>
          <span className="font-semibold text-muted-foreground">{parts.join(", ") || t("delivery.page.nothing")}</span>
        </p>
        {chips.length > 0 && (
          <ul aria-label={t("delivery.page.places", { name })} className="flex flex-wrap gap-1.5">
            {chips.map(({ kind, place }) => (
              <li
                key={placeKey(kind, place.id)}
                className={cn(
                  "flex min-h-9 items-center gap-1 rounded-full border bg-card pl-2.5 text-sm",
                  kind === "district" ? "border-mint-line" : "border-[#C9D6EE]",
                )}
              >
                <span className={cn("text-xs font-bold", kind === "district" ? "text-primary" : "text-[#24427A]")}>
                  {t(kind === "district" ? "delivery.page.district" : "delivery.page.area")}
                </span>
                <span className="font-bold">{placeName(place)}</span>
                <button
                  type="button"
                  aria-label={t("delivery.page.removePlace", { name: placeName(place) })}
                  className="flex size-8 cursor-pointer items-center justify-center rounded-full text-muted-foreground hover:bg-muted"
                  onClick={() => remove(kind, place)}
                >
                  <X aria-hidden="true" className="size-3.5" />
                </button>
              </li>
            ))}
          </ul>
        )}
        {chips.length === 0 && (
          <p className="text-sm font-semibold text-warning-foreground">{t("delivery.page.noPlaces")}</p>
        )}
        {zone.moved.map((note) => (
          <div
            key={note.key}
            role="status"
            className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-[#FFF4DE] px-3 py-2 text-sm text-[#6B4A10]"
          >
            <span>{t("delivery.page.moved", { place: placeName(note.place), from: note.from })}</span>
            <button
              type="button"
              className="min-h-9 cursor-pointer rounded-[10px] border-[1.5px] border-[#C9A25A] bg-card px-3 text-sm font-bold"
              onClick={() => onMoveBack(note)}
            >
              {t("delivery.page.moveBack")}
            </button>
          </div>
        ))}
        <button
          type="button"
          className="flex min-h-10 w-fit cursor-pointer items-center gap-1.5 rounded-[10px] border-[1.5px] border-dashed border-[#9FC7B6] bg-card px-3 text-sm font-bold text-primary hover:bg-accent"
          onClick={() => setAdding(true)}
        >
          <Plus aria-hidden="true" className="size-3.5" strokeWidth={2.6} />
          {t("delivery.page.addPlaces")}
        </button>
      </div>
      {adding && (
        <Suspense fallback={null}>
          <PlacesDialog
            zone={name}
            owners={owners}
            onClose={() => setAdding(false)}
            onAdd={(places) => {
              onAdd(places);
              setAdding(false);
            }}
          />
        </Suspense>
      )}
    </section>
  );
}

/** The zone for everywhere else: a name and a charge, always last. */
function RestCard({
  zone,
  problems,
  onChange,
}: {
  zone: Draft;
  problems?: Problems;
  onChange: (change: Partial<Draft>) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section
      aria-label={t("delivery.page.rest")}
      className="flex flex-wrap items-center gap-3 rounded-2xl border-[1.5px] border-[#E8D3A6] bg-[#FFF9EE] p-3.5 md:p-4.5"
    >
      <span
        aria-hidden="true"
        className="flex size-8 flex-none items-center justify-center rounded-[10px] bg-marigold text-ledger"
      >
        <Globe className="size-4.5" />
      </span>
      <div className="flex min-w-0 flex-[1_1_200px] flex-col gap-1">
        <label htmlFor={`${id}-name`} className="text-[13px] font-semibold text-muted-foreground">
          {t("delivery.page.restName")}
        </label>
        <input
          id={`${id}-name`}
          maxLength={50}
          aria-invalid={problems?.name ? true : undefined}
          aria-describedby={problems?.name ? `${id}-name-error` : undefined}
          className={cn(FIELD, problems?.name ? "border-destructive" : "border-input")}
          value={zone.name}
          onChange={(event) => onChange({ name: event.target.value })}
        />
        <p className="text-[13px] text-[#6B4A10]">{t("delivery.page.restHint")}</p>
        {problems?.name && (
          <p id={`${id}-name-error`} className="text-sm text-destructive">
            {t(problems.name)}
          </p>
        )}
      </div>
      <div className="flex flex-col gap-1">
        <Charge
          id={`${id}-charge`}
          name={t("delivery.page.rest")}
          value={zone.charge}
          error={problems?.charge}
          onChange={(charge) => onChange({ charge })}
        />
        {problems?.charge && (
          <p id={`${id}-charge-error`} className="text-sm text-destructive">
            {t(problems.charge)}
          </p>
        )}
      </div>
    </section>
  );
}

/** What an order to a place would be charged, by the saved zones: the server's own quote. */
function QuoteCheck({ zones, dirty }: { zones: Zone[]; dirty: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const placeName = usePlaceName();
  const { groups } = useDistrictGroups();
  const [districtId, setDistrictId] = useState<number>();
  const [areaId, setAreaId] = useState<number>();
  const { data: areas } = useQuery({ ...areasQuery(districtId ?? 0), enabled: districtId !== undefined });
  const { data: quote } = useQuery({
    queryKey: ["delivery-quote", districtId, areaId],
    queryFn: () => call(api.GET("/api/v1/delivery-zones/quote", { params: { query: { districtId, areaId } } })),
    enabled: districtId !== undefined,
  });
  const ordered = [...zones.filter((zone) => !zone.isDefault), ...zones.filter((zone) => zone.isDefault)];
  return (
    <aside
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-3 rounded-2xl border bg-card p-3.5 max-md:order-first md:sticky md:top-6 md:p-4.5"
    >
      <div>
        <h2 id={`${id}-title`} className="text-[19px] font-bold">
          {t("delivery.page.check")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("delivery.page.checkHint")}</p>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={`${id}-district`} className="text-sm font-semibold">
            {t("delivery.page.checkDistrict")}
          </label>
          <select
            id={`${id}-district`}
            className="h-11.5 min-w-0 rounded-[10px] border-[1.5px] border-input bg-card px-2 text-[15px]"
            value={districtId ?? ""}
            onChange={(event) => {
              setDistrictId(event.target.value ? Number(event.target.value) : undefined);
              setAreaId(undefined);
            }}
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
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={`${id}-area`} className="text-sm font-semibold">
            {t("delivery.page.checkArea")}
          </label>
          <select
            id={`${id}-area`}
            disabled={districtId === undefined}
            className="h-11.5 min-w-0 rounded-[10px] border-[1.5px] border-input bg-card px-2 text-[15px] disabled:opacity-60"
            value={areaId ?? ""}
            onChange={(event) => setAreaId(event.target.value ? Number(event.target.value) : undefined)}
          >
            <option value="">{t("delivery.page.wholeDistrict")}</option>
            {areas?.map((place) => (
              <option key={place.id} value={place.id}>
                {placeName(place)}
              </option>
            ))}
          </select>
        </div>
      </div>
      {quote && districtId !== undefined && (
        <div role="status" className="flex items-center gap-3.5 rounded-[14px] bg-ledger p-3.5 text-white">
          <span className="text-[32px] leading-none font-bold text-marigold-text">{format.taka(quote.charge)}</span>
          <span className="min-w-0">
            <span className="block font-bold break-words">{quote.zoneName}</span>
            <span className="block text-[13px] text-ledger-text">{t(`delivery.page.by${quote.matchedBy}`)}</span>
          </span>
        </div>
      )}
      {dirty && <p className="text-[13px] text-warning-foreground">{t("delivery.page.checkUnsaved")}</p>}
      {ordered.length > 0 && (
        <div className="flex flex-col gap-1.5 border-t pt-2.5">
          <p className="text-sm font-bold">{t("delivery.page.glance")}</p>
          {ordered.map((zone, index) => (
            <p key={zone.id} className="flex items-center gap-2 text-sm">
              <span
                aria-hidden="true"
                className={cn(
                  "size-2.5 flex-none rounded-[3px]",
                  zone.isDefault ? "bg-marigold" : tintOf(index).swatch,
                )}
              />
              <span className="min-w-0 flex-1 break-words">{zone.name}</span>
              <strong>{format.taka(zone.charge)}</strong>
            </p>
          ))}
        </div>
      )}
    </aside>
  );
}
