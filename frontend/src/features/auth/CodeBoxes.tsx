import { useId } from "react";
import type { FieldValues, Path } from "react-hook-form";
import type { FormOf } from "@/components/form/fields";
import { Label } from "@/components/ui/label";
import i18n from "@/i18n";
import { cn } from "@/lib/utils";

const BOXES = [0, 1, 2, 3, 4, 5];

/**
 * Six boxes that are really one input laid over them: one field to a screen reader, and pasting or
 * the phone offering the code from an SMS work as in any input. Bangla digits are kept here and
 * become Latin in the form's rules.
 */
export function CodeBoxes<T extends FieldValues>({
  form,
  name,
  label,
}: {
  form: FormOf<T>;
  name: Path<T>;
  label: string;
}) {
  const id = useId();
  const value = String(form.watch(name) ?? "");
  const message = form.getFieldState(name, form.formState).error?.message;
  const error = message && i18n.exists(message) ? i18n.t(message as never) : message;
  const field = form.register(name);
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </Label>
      <div className="group relative">
        <div aria-hidden="true" className="grid grid-cols-6 gap-2">
          {BOXES.map((box) => (
            <span
              key={box}
              className={cn(
                "flex h-14.5 items-center justify-center rounded-[10px] border-[1.5px] bg-card text-2xl font-semibold",
                error ? "border-destructive" : "border-input",
                box === Math.min(value.length, 5) &&
                  "group-has-focus:border-ring group-has-focus:ring-3 group-has-focus:ring-ring/50",
              )}
            >
              {value[box]}
            </span>
          ))}
        </div>
        <input
          id={id}
          className="absolute inset-0 size-full cursor-text text-base opacity-0"
          inputMode="numeric"
          autoComplete="one-time-code"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          {...field}
          onChange={(event) => {
            event.target.value = event.target.value.replace(/[^\d০-৯]/g, "").slice(0, BOXES.length);
            return field.onChange(event);
          }}
        />
      </div>
      {error && (
        <p id={`${id}-error`} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
