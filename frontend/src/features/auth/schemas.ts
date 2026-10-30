import { z } from "zod";
import { latinDigits } from "@/lib/digits";

// Messages are text keys (i18n/*.json), so an error on screen follows the language.
const required = z.string().trim().min(1, "field.required");

/** A phone number or an email address. */
export const login = required.transform(latinDigits);
export const phone = required.transform(latinDigits);
export const code = z
  .string()
  .trim()
  .transform(latinDigits)
  .pipe(z.string().regex(/^\d{6}$/, "field.sixDigits"));
export const currentPassword = z.string().min(1, "field.required");
export const newPassword = z.string().min(8, "field.passwordShort").max(128, "field.passwordLong");
/** Six digits from the authenticator app, or a recovery code (XXXXX-XXXXX). */
export const appOrRecoveryCode = required.max(20, "field.codeLong").transform(latinDigits);
export const name = required.max(120, "field.nameLong");
