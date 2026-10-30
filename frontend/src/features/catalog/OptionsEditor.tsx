import { Plus, X } from "lucide-react";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";

export interface OptionDraft {
  /** The saved option's id, so renaming it keeps its variants. */
  id?: string;
  name: string;
  values: string[];
}

export const MAX_OPTIONS = 3;
export const MAX_VARIANTS = 100;

/** How many variants these options make, one per combination; 1 without options. */
export function variantCount(options: OptionDraft[]): number {
  return options.reduce((count, option) => count * Math.max(option.values.length, 1), 1);
}

/** The first problem with these options, as a text key; the server checks again. */
export function optionsProblem(
  options: OptionDraft[],
): "products.options.incomplete" | "products.options.tooMany" | null {
  if (options.some((option) => !option.name.trim() || option.values.length === 0)) {
    return "products.options.incomplete";
  }
  return variantCount(options) > MAX_VARIANTS ? "products.options.tooMany" : null;
}

/** Size: S, M, L and so on: each combination of the values becomes a variant. */
export function OptionsEditor({
  options,
  onChange,
}: {
  options: OptionDraft[];
  onChange: (next: OptionDraft[]) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const update = (index: number, next: OptionDraft) => onChange(options.map((old, i) => (i === index ? next : old)));
  return (
    <div className="flex flex-col gap-3">
      {options.map((option, index) => (
        <OptionRow
          // biome-ignore lint/suspicious/noArrayIndexKey: options have no identity until saved, and are only added or removed.
          key={index}
          number={index + 1}
          option={option}
          onChange={(next) => update(index, next)}
          onRemove={() => onChange(options.filter((_, i) => i !== index))}
        />
      ))}
      <div className="flex flex-wrap items-center gap-3">
        {options.length < MAX_OPTIONS && (
          <Button type="button" variant="outline" onClick={() => onChange([...options, { name: "", values: [] }])}>
            <Plus aria-hidden="true" />
            {t("products.options.add")}
          </Button>
        )}
        {options.length > 0 && (
          <p className="text-sm font-medium" role="status">
            {t("products.options.makes", { count: format.number(variantCount(options)) })}
          </p>
        )}
      </div>
    </div>
  );
}

function OptionRow({
  number,
  option,
  onChange,
  onRemove,
}: {
  number: number;
  option: OptionDraft;
  onChange: (next: OptionDraft) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const shownNumber = format.number(number);
  const id = useId();
  const [typed, setTyped] = useState("");

  // A value is added with its button, Enter or a comma, so a list can be typed or pasted in one go.
  function addTyped(text: string) {
    const added = text
      .split(",")
      .map((value) => value.trim())
      .filter((value) => value && !option.values.includes(value));
    if (added.length > 0) {
      onChange({ ...option, values: [...option.values, ...new Set(added)] });
    }
    setTyped("");
  }

  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-3">
      <legend className="px-1 text-sm text-muted-foreground">
        {t("products.options.number", { number: shownNumber })}
      </legend>
      <div className="flex items-end gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <Label htmlFor={`${id}-name`} className="text-[15px] leading-normal font-semibold">
            {t("products.options.name")}
          </Label>
          <Input
            id={`${id}-name`}
            value={option.name}
            maxLength={30}
            placeholder={t("products.options.nameHint")}
            onChange={(event) => onChange({ ...option, name: event.target.value })}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="icon"
          aria-label={t("products.options.remove", { number: shownNumber })}
          onClick={onRemove}
        >
          <X aria-hidden="true" />
        </Button>
      </div>
      {option.values.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {option.values.map((value) => (
            <li key={value} className="flex items-center gap-1 rounded-lg border border-input bg-card pl-3">
              {value}
              <button
                type="button"
                aria-label={t("products.options.removeValue", { value })}
                className="flex size-9 cursor-pointer items-center justify-center text-muted-foreground"
                onClick={() => onChange({ ...option, values: option.values.filter((old) => old !== value) })}
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex items-end gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <Label htmlFor={`${id}-value`} className="text-[15px] leading-normal font-semibold">
            {t("products.options.value")}
          </Label>
          <Input
            id={`${id}-value`}
            value={typed}
            maxLength={200}
            placeholder={t("products.options.valueHint")}
            onChange={(event) =>
              event.target.value.includes(",") ? addTyped(event.target.value) : setTyped(event.target.value)
            }
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addTyped(typed);
              }
            }}
            onBlur={() => addTyped(typed)}
          />
        </div>
        <Button type="button" variant="outline" onClick={() => addTyped(typed)}>
          {t("products.options.addValue")}
        </Button>
      </div>
    </fieldset>
  );
}
