import type { FieldValues, Path, UseFormReturn } from "react-hook-form";
import { ApiError, errorAction } from "@/api/errors";
import { errorText } from "@/api/query";

interface Mapping<T extends FieldValues> {
  /** The server's field name, where it differs from the form's: the code request calls it `to`. */
  fields?: Record<string, Path<T>>;
  /** Error codes that belong to one field: `otp.invalid` to the code. */
  codes?: Record<string, Path<T>>;
}

/** Shows a refused request in the form: under the fields it names, or above the form otherwise. */
export function showServerError<T extends FieldValues>(
  form: UseFormReturn<T>,
  error: unknown,
  mapping: Mapping<T> = {},
) {
  const action = errorAction(error);
  if (action.kind === "fields") {
    const known = Object.keys(form.getValues());
    let shown = false;
    for (const problem of action.fields) {
      const field = mapping.fields?.[problem.field] ?? problem.field;
      if (known.includes(field)) {
        form.setError(field as Path<T>, { type: "server", message: problem.message });
        shown = true;
      }
    }
    if (shown) {
      return;
    }
  }
  const field = error instanceof ApiError ? mapping.codes?.[error.code] : undefined;
  if (field) {
    form.setError(field, { type: "server", message: errorText(error) });
  } else {
    form.setError("root", { type: "server", message: errorText(error) });
  }
}
