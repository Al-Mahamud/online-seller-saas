import { z } from "zod";
import { parseCount, parseTaka } from "@/lib/money";

/** The most a price can be: ৳10,000,000,000, in poisha. */
const MAX_POISHA = 1_000_000_000_000;

/** An amount typed in taka, either digits, to whole poisha. */
export const taka = z
  .string()
  .transform((text) => parseTaka(text))
  .refine((poisha) => poisha !== null && poisha <= MAX_POISHA, "field.amountInvalid")
  .transform((poisha) => poisha as number);

/** The same, or nothing: an empty box. */
export const optionalTaka = z
  .string()
  .transform((text) => (text.trim() ? parseTaka(text) : undefined))
  .refine((poisha) => poisha !== null && (poisha === undefined || poisha <= MAX_POISHA), "field.amountInvalid")
  .transform((poisha) => poisha as number | undefined);

/** A whole number or an empty box. */
export const optionalCount = (max: number) =>
  z
    .string()
    .transform((text) => (text.trim() ? parseCount(text) : undefined))
    .refine((count) => count !== null && (count === undefined || count <= max), "field.countInvalid")
    .transform((count) => count as number | undefined);
