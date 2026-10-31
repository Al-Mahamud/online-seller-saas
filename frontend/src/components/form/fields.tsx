import { Eye, EyeOff, X } from "lucide-react";
import { type ComponentProps, useId, useState } from "react";
import type { FieldValues, Path, UseFormReturn } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import i18n from "@/i18n";

/** Our own messages arrive from the form's rules as text keys; the server's are already translated. */
function fieldMessage(message: string | undefined): string | undefined {
  return message && i18n.exists(message) ? i18n.t(message as never) : message;
}

/** Any form over these field values, whatever its rules turn them into when submitted (text to a number). */
// biome-ignore lint/suspicious/noExplicitAny: the submitted type does not matter to a field.
export type FormOf<T extends FieldValues> = UseFormReturn<T, any, any>;

type InputProps = Omit<ComponentProps<typeof Input>, "form" | "name">;

interface FieldProps<T extends FieldValues> extends InputProps {
  form: FormOf<T>;
  name: Path<T>;
  label: string;
  hint?: React.ReactNode;
  /** A link on the label's line, such as "forgot your password?". */
  labelAction?: React.ReactNode;
  /** Inside the input, at its end. */
  inside?: React.ReactNode;
  /** Next to the input, such as a button that acts on it. */
  beside?: React.ReactNode;
  /** A cross with the error, so it doesn't rest on colour alone. */
  errorIcon?: boolean;
}

/** A labelled input with its hint and its error, tied to the form for screen readers. */
export function TextField<T extends FieldValues>({
  form,
  name,
  label,
  hint,
  labelAction,
  inside,
  beside,
  errorIcon = false,
  ...input
}: FieldProps<T>) {
  const id = useId();
  const error = fieldMessage(form.getFieldState(name, form.formState).error?.message);
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(" ") || undefined;
  const labelled = (
    <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
      {label}
    </Label>
  );
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      {labelAction ? (
        <div className="flex min-h-6 flex-wrap items-center justify-between gap-x-3">
          {labelled}
          {labelAction}
        </div>
      ) : (
        labelled
      )}
      <div className="flex gap-2">
        <div className="relative min-w-0 flex-1">
          <Input
            id={id}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
            {...input}
            {...form.register(name)}
          />
          {inside && <div className="absolute inset-y-0 right-0.5 flex items-center">{inside}</div>}
        </div>
        {beside}
      </div>
      {hint && (
        <div id={`${id}-hint`} className="text-sm text-muted-foreground">
          {hint}
        </div>
      )}
      {error && (
        <p id={`${id}-error`} className="flex items-start gap-1.5 text-sm text-destructive">
          {errorIcon && <X aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2.5} />}
          {error}
        </p>
      )}
    </div>
  );
}

/** A native select: on phones it opens the phone's own picker. */
export function SelectField<T extends FieldValues>({
  form,
  name,
  label,
  children,
}: {
  form: FormOf<T>;
  name: Path<T>;
  label: string;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </Label>
      <select
        id={id}
        className="h-12 w-full rounded-lg border border-input bg-card px-2 md:h-11"
        {...form.register(name)}
      >
        {children}
      </select>
    </div>
  );
}

/** Longer text, with the same label, hint and error as a TextField. */
export function TextAreaField<T extends FieldValues>({
  form,
  name,
  label,
  hint,
}: {
  form: FormOf<T>;
  name: Path<T>;
  label: string;
  hint?: string;
}) {
  const id = useId();
  const error = fieldMessage(form.getFieldState(name, form.formState).error?.message);
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(" ") || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </Label>
      <Textarea
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...form.register(name)}
      />
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

/** A password, which the person can choose to see: typing on a phone is error-prone. */
export function PasswordField<T extends FieldValues>(props: FieldProps<T>) {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  return (
    <TextField
      {...props}
      type={visible ? "text" : "password"}
      className="pr-12"
      inside={
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={visible ? t("field.hidePassword") : t("field.showPassword")}
          aria-pressed={visible}
          onClick={() => setVisible(!visible)}
        >
          {visible ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
        </Button>
      }
    />
  );
}

/** The six digits of a code; phones offer them from the SMS when they can. */
export function CodeField<T extends FieldValues>(props: FieldProps<T>) {
  return <TextField inputMode="numeric" autoComplete="one-time-code" maxLength={6} {...props} />;
}

/** A problem with the form as a whole, such as a wrong password; `strong` for the sign-in pages. */
export function FormError<T extends FieldValues>({ form, strong = false }: { form: FormOf<T>; strong?: boolean }) {
  const message = fieldMessage(form.formState.errors.root?.message);
  if (!message) {
    return null;
  }
  return strong ? (
    <Alert variant="destructive" className="rounded-[10px] border-0 bg-danger-soft px-3.5 py-3 text-danger-ink">
      <X aria-hidden="true" strokeWidth={2.5} />
      <AlertDescription className="text-[15px] font-semibold text-danger-ink">{message}</AlertDescription>
    </Alert>
  ) : (
    <Alert variant="destructive">
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}
