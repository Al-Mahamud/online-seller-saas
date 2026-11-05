/**
 * What the admin routes read from the address. Kept apart from the queries: route options load
 * with the app for everyone, and only admins need the rest.
 */
export type BusinessState = "ACTIVE" | "GRACE" | "READ_ONLY" | "SUSPENDED";

export const BUSINESS_STATES: BusinessState[] = ["ACTIVE", "GRACE", "READ_ONLY", "SUSPENDED"];

/** The admin log's chips, and the actions each one shows. */
export const AUDIT_KINDS = {
  transfers: ["invoice.marked_paid"],
  plans: ["plan.set"],
  suspensions: ["business.suspended", "business.unsuspended"],
  edits: ["plan.edited"],
} as const;

export type AuditKind = keyof typeof AUDIT_KINDS;
