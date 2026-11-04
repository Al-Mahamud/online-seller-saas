import type { LinkProps } from "@tanstack/react-router";
import {
  Banknote,
  ChartColumn,
  CreditCard,
  House,
  type LucideIcon,
  Package,
  PackageX,
  ReceiptText,
  ScrollText,
  Store,
  Tags,
  Truck,
  UserPlus,
  Users,
} from "lucide-react";
import { type Business, can, hasRole, type Permission } from "@/api/business";

export type NavLabel =
  | "nav.today"
  | "nav.orders"
  | "nav.products"
  | "damages.title"
  | "customers.title"
  | "cod.title"
  | "reports.title"
  | "billing.title"
  | "business.title"
  | "categories.title"
  | "delivery.title"
  | "staff.title"
  | "audit.title";

export interface NavLink {
  to: LinkProps["to"];
  label: NavLabel;
  icon: LucideIcon;
  exact?: boolean;
  /** Who may open it, by role or by area (any one); the page itself checks too. */
  role?: Parameters<typeof hasRole>[1];
  areas?: Permission[];
}

/** Whether this member's menus show the link. */
export function linkAllowed(link: NavLink, business: Business): boolean {
  return (!link.role || hasRole(business, link.role)) && (!link.areas || can(business, ...link.areas));
}

/** The phone's tabs hold the first three; the rest are on its "More" page. */
export const TAB_LINKS: NavLink[] = [
  { to: "/", label: "nav.today", icon: House, exact: true },
  { to: "/orders", label: "nav.orders", icon: ReceiptText, areas: ["ORDERS"] },
  { to: "/products", label: "nav.products", icon: Package },
];

export const WORK_LINKS: NavLink[] = [
  { to: "/damages", label: "damages.title", icon: PackageX, areas: ["STOCK"] },
  { to: "/customers", label: "customers.title", icon: Users, areas: ["ORDERS", "CUSTOMERS"] },
  { to: "/cod", label: "cod.title", icon: Banknote, areas: ["ORDERS"] },
  { to: "/reports", label: "reports.title", icon: ChartColumn },
];

export const MANAGE_LINKS: NavLink[] = [
  { to: "/billing", label: "billing.title", icon: CreditCard, areas: ["SETTINGS"] },
  { to: "/settings/business", label: "business.title", icon: Store, areas: ["SETTINGS"] },
  { to: "/settings/categories", label: "categories.title", icon: Tags, areas: ["PRODUCTS"] },
  { to: "/settings/delivery", label: "delivery.title", icon: Truck, areas: ["SETTINGS"] },
  { to: "/settings/staff", label: "staff.title", icon: UserPlus, areas: ["TEAM"] },
  { to: "/settings/audit-log", label: "audit.title", icon: ScrollText, role: "OWNER" },
];
