import { type ComponentProps, useId } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

interface Labelled {
  label: string;
  hint?: string;
  error?: string;
}

function Around({ id, label, hint, error, children }: Labelled & { id: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </Label>
      {children}
      {hint && (
        <p id={`${id}-hint`} className="text-sm text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

const describedBy = (id: string, hint?: string, error?: string) =>
  [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(" ") || undefined;

/**
 * A labelled input for a screen that keeps its own state, without the form libraries: the order
 * screens have many amounts and lines that depend on each other, and these pages stay small.
 */
export function Field({ label, hint, error, ...input }: Labelled & ComponentProps<typeof Input>) {
  const id = useId();
  return (
    <Around id={id} label={label} hint={hint} error={error}>
      <Input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        {...input}
      />
    </Around>
  );
}

export function AreaField({ label, hint, error, ...input }: Labelled & ComponentProps<typeof Textarea>) {
  const id = useId();
  return (
    <Around id={id} label={label} hint={hint} error={error}>
      <Textarea
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, hint, error)}
        {...input}
      />
    </Around>
  );
}

/** A native select: on phones it opens the phone's own picker. */
export function SelectBox({ label, hint, error, children, ...select }: Labelled & ComponentProps<"select">) {
  const id = useId();
  return (
    <Around id={id} label={label} hint={hint} error={error}>
      <select
        id={id}
        className="h-12 w-full rounded-lg border border-input bg-card px-2 md:h-11"
        aria-describedby={describedBy(id, hint, error)}
        {...select}
      >
        {children}
      </select>
    </Around>
  );
}
