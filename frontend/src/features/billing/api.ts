import { type QueryClient, queryOptions } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import i18n from "@/i18n";
import { type Paging, pagedQuery } from "@/lib/paging";

export type Billing = components["schemas"]["Billing"];
export type Plan = components["schemas"]["BillingPlanView"];
export type Invoice = components["schemas"]["InvoicesInvoiceView"];
export type PayBy = "SSLCOMMERZ" | "BANK_TRANSFER";

export const billingQuery = queryOptions({
  queryKey: ["billing"],
  queryFn: () => call(api.GET("/api/v1/billing")),
});

export const plansQuery = queryOptions({
  queryKey: ["plans"],
  queryFn: () => call(api.GET("/api/v1/plans")),
});

export const invoicesQuery = (paging: Paging) =>
  pagedQuery(["invoices", paging], () => call(api.GET("/api/v1/billing/invoices", { params: { query: paging } })));

export const invoiceQuery = (id: string) =>
  queryOptions({
    queryKey: ["invoice", id],
    queryFn: () => call(api.GET("/api/v1/billing/invoices/{id}", { params: { path: { id } } })),
  });

/** `months` left out: an upgrade for the rest of the running period. */
export const quoteQuery = (planCode: string, months: number | undefined) =>
  queryOptions({
    queryKey: ["billing-quote", planCode, months],
    queryFn: () => call(api.GET("/api/v1/billing/quote", { params: { query: { planCode, months } } })),
    meta: { showsOwnErrors: true },
  });

export function billingChanged(queryClient: QueryClient) {
  for (const key of ["billing", "subscription-status", "invoices", "invoice"]) {
    void queryClient.invalidateQueries({ queryKey: [key] });
  }
}

/** A plan's name in the language on screen; an invoice names its plan only by code. */
export function usePlanName() {
  const { i18n: current } = useTranslation();
  return (plan: Plan) => (current.resolvedLanguage === "en" ? plan.nameEn : plan.nameBn);
}

/** The invoice as a page to print or save as PDF, in the language on screen. */
export function invoicePrintUrl(id: string): string {
  return `/api/v1/billing/invoices/${id}/print?lang=${i18n.resolvedLanguage === "en" ? "en" : "bn"}`;
}
