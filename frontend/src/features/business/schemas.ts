import { z } from "zod/mini";
import { name } from "@/features/auth/schemas";
import { latinDigits } from "@/lib/digits";

export const businessFields = {
  name,
  phone: z.pipe(z.string().check(z.trim()), z.transform(latinDigits)),
  address: z.string().check(z.trim(), z.maxLength(300, "field.addressLong")),
};
