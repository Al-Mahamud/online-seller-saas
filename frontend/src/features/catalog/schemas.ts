import { z } from "zod/mini";
import { parseCount, parseTaka } from "@/lib/money";

/** The most a price can be: ৳10,000,000,000, in poisha. */
const MAX_POISHA = 1_000_000_000_000;

/** An amount typed in taka, either digits, to whole poisha. */
export const taka = z.pipe(
  z
    .pipe(
      z.string(),
      z.transform((text: string) => parseTaka(text)),
    )
    .check(z.refine((poisha) => poisha !== null && poisha <= MAX_POISHA, "field.amountInvalid")),
  z.transform((poisha) => poisha as number),
);

/** The same, or nothing: an empty box. */
export const optionalTaka = z.pipe(
  z
    .pipe(
      z.string(),
      z.transform((text: string) => (text.trim() ? parseTaka(text) : undefined)),
    )
    .check(
      z.refine((poisha) => poisha !== null && (poisha === undefined || poisha <= MAX_POISHA), "field.amountInvalid"),
    ),
  z.transform((poisha) => poisha as number | undefined),
);

/** A whole number or an empty box. */
export const optionalCount = (max: number) =>
  z.pipe(
    z
      .pipe(
        z.string(),
        z.transform((text: string) => (text.trim() ? parseCount(text) : undefined)),
      )
      .check(z.refine((count) => count !== null && (count === undefined || count <= max), "field.countInvalid")),
    z.transform((count) => count as number | undefined),
  );

const variantText = z.string().check(z.trim(), z.maxLength(30, "products.variantRows.textLong"));

/** One variant as the seller types it; empty name and size are fine for a product of one kind. */
export const variantRow = z.object({
  name: variantText,
  size: variantText,
  quantity: optionalCount(1_000_000),
  costPrice: optionalTaka,
  price: z.pipe(z.string().check(z.trim(), z.minLength(1, "products.variantRows.priceNeeded")), taka),
});
