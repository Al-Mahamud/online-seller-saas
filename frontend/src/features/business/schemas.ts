import { z } from "zod";
import { name } from "@/features/auth/schemas";
import { latinDigits } from "@/lib/digits";

export const businessFields = {
  name,
  phone: z.string().trim().transform(latinDigits),
  address: z.string().trim().max(300, "field.addressLong"),
};
