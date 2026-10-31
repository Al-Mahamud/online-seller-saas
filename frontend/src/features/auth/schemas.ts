import { z } from "zod/mini";
import { latinDigits } from "@/lib/digits";

// Messages are text keys (i18n/*.json), so an error on screen follows the language.
const required = z.string().check(z.trim(), z.minLength(1, "field.required"));

const lowerCase = z.pipe(
  required,
  z.transform((value) => value.toLowerCase()),
);

/** A User ID typed to sign in. Not checked against the rules: a wrong one gets the wrong-details answer. */
export const userId = lowerCase;
/** A User ID being chosen; the server keeps the same rules (UserIds.java). */
export const newUserId = z.pipe(lowerCase, z.string().check(z.regex(/^[a-z0-9_]{4,20}$/, "auth.userId.notAllowed")));
/** A phone number or an email address. */
export const login = z.pipe(required, z.transform(latinDigits));
export const phone = z.pipe(required, z.transform(latinDigits));
export const code = z.pipe(
  z.pipe(z.string().check(z.trim()), z.transform(latinDigits)),
  z.string().check(z.regex(/^\d{6}$/, "field.sixDigits")),
);
export const currentPassword = z.string().check(z.minLength(1, "field.required"));
export const newPassword = z
  .string()
  .check(z.minLength(8, "field.passwordShort"), z.maxLength(128, "field.passwordLong"));
/** Six digits from the authenticator app, or a recovery code (XXXXX-XXXXX). */
export const appOrRecoveryCode = z.pipe(required.check(z.maxLength(20, "field.codeLong")), z.transform(latinDigits));
export const name = required.check(z.maxLength(120, "field.nameLong"));
