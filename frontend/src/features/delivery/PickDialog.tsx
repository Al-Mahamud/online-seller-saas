import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { matchesPlace, type Named, usePlaceName } from "./geo";

export interface Group {
  name: string | null;
  places: Named[];
}

/** A list to choose one from, with a search box: quicker than three dropdowns on a phone. */
export default function PickDialog({
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
