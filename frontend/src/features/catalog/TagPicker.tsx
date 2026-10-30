import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { tagsQuery } from "./api";

/** Tap to put a tag on the product; a new one is created on the spot. */
export function TagPicker({ selected, onChange }: { selected: string[]; onChange: (next: string[]) => void }) {
  const { t } = useTranslation();
  const id = useId();
  const queryClient = useQueryClient();
  const { data: tags } = useQuery(tagsQuery);
  const [typed, setTyped] = useState("");
  const create = useMutation({
    mutationFn: (name: string) => call(api.POST("/api/v1/tags", { body: { name } })),
    meta: { showsOwnErrors: true },
    onSuccess: (tag) => {
      setTyped("");
      onChange([...selected, tag.id]);
      void queryClient.invalidateQueries(tagsQuery);
    },
  });
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-[15px] font-semibold">{t("products.tags")}</legend>
      {!!tags?.length && (
        <div className="flex flex-wrap gap-2">
          {tags.map((tag) => {
            const on = selected.includes(tag.id);
            return (
              <button
                key={tag.id}
                type="button"
                aria-pressed={on}
                className={cn(
                  "h-9 cursor-pointer rounded-full border px-3.5 text-sm",
                  on ? "border-foreground bg-foreground text-background" : "border-input bg-card",
                )}
                onClick={() => onChange(on ? selected.filter((old) => old !== tag.id) : [...selected, tag.id])}
              >
                {tag.name}
              </button>
            );
          })}
        </div>
      )}
      <div className="flex items-end gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <Label htmlFor={id} className="text-sm font-normal text-muted-foreground">
            {t("products.newTag")}
          </Label>
          <Input
            id={id}
            value={typed}
            maxLength={50}
            onChange={(event) => setTyped(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                if (typed.trim()) {
                  create.mutate(typed.trim());
                }
              }
            }}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          disabled={!typed.trim() || create.isPending}
          onClick={() => create.mutate(typed.trim())}
        >
          {t("products.addTag")}
        </Button>
      </div>
      {create.error && <p className="text-sm text-destructive">{errorText(create.error)}</p>}
    </fieldset>
  );
}
