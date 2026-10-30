import { Eye, EyeOff } from "lucide-react";
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
  hint?: string;
}

/** A labelled input with its hint and its error, tied to the form for screen readers. */
export function TextField<T extends FieldValues>({ form, name, label, hint, ...input }: FieldProps<T>) {
  const id = useId();
  const error = fieldMessage(form.getFieldState(name, form.formState).error?.message);
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(" ") || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id} className="text-[15px] leading-normal font-semibold">
        {label}
      </Label>
      <Input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...input}
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
    <div className="relative">
      <TextField {...props} type={visible ? "text" : "password"} className="pr-12" />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="absolute top-[calc(1.5rem+6px)] right-0.5 md:top-[calc(1.5rem+4px)]"
        aria-label={visible ? t("field.hidePassword") : t("field.showPassword")}
        aria-pressed={visible}
        onClick={() => setVisible(!visible)}
      >
        {visible ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
      </Button>
    </div>
  );
}

/** The six digits of a code; phones offer them from the SMS when they can. */
export function CodeField<T extends FieldValues>(props: FieldProps<T>) {
  return <TextField inputMode="numeric" autoComplete="one-time-code" maxLength={6} {...props} />;
}

/** A problem with the form as a whole, such as a wrong password. */
export function FormError<T extends FieldValues>({ form }: { form: FormOf<T> }) {
  const message = fieldMessage(form.formState.errors.root?.message);
  return message ? (
    <Alert variant="destructive">
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  ) : null;
}
