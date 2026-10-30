import i18n from "@/i18n";

/** A subscription's status, with the same words the business itself sees. */
export function statusText(status: string): string {
  return i18n.t(`billing.status.${status}` as "billing.status.ACTIVE");
}
