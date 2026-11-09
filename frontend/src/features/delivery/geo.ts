import { queryOptions } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { latinDigits } from "@/lib/digits";
import { containsText } from "@/lib/search";

export type Division = components["schemas"]["GeoDirectoryDivision"];
export type District = components["schemas"]["GeoDirectoryDistrict"];
export type Area = components["schemas"]["GeoDirectoryArea"];

/** A place with its name in both languages; the places of Bangladesh rarely change. */
export interface Named {
  id: number;
  nameBn: string;
  nameEn: string;
}

const LONG_TIME = 24 * 60 * 60_000;

export const divisionsQuery = queryOptions({
  queryKey: ["geo", "divisions"],
  queryFn: () => call(api.GET("/api/v1/geo/divisions")),
  staleTime: LONG_TIME,
});

export const districtsQuery = queryOptions({
  queryKey: ["geo", "districts"],
  queryFn: () => call(api.GET("/api/v1/geo/districts")),
  staleTime: LONG_TIME,
});

export const areasQuery = (districtId: number) =>
  queryOptions({
    queryKey: ["geo", "areas", districtId],
    queryFn: () => call(api.GET("/api/v1/geo/districts/{id}/areas", { params: { path: { id: districtId } } })),
    staleTime: LONG_TIME,
  });

/** A place's name in the language on screen. */
export function usePlaceName() {
  const { i18n } = useTranslation();
  return (place: Named) => (i18n.resolvedLanguage === "en" ? place.nameEn : place.nameBn);
}

/** Whether a place's name, in either language, has this text in it. */
export function matchesPlace(place: Named, search: string): boolean {
  const wanted = latinDigits(search).trim();
  return !wanted || containsText(place.nameBn, wanted) || containsText(place.nameEn, wanted);
}
