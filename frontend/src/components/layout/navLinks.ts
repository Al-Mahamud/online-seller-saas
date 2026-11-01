import type { LinkProps } from "@tanstack/react-router";
import {
  Banknote,
  ChartColumn,
  CreditCard,
  House,
  type LucideIcon,
  Package,
  ReceiptText,
  ScrollText,
  Store,
  Tags,
  Truck,
  UserPlus,
  Users,
} from "lucide-react";
import type { hasRole } from "@/api/business";

export type NavLabel =
  | "nav.today"
  | "nav.orders"
  | "nav.products"
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
  /** Who may open it; the page itself checks too. */
  role?: Parameters<typeof hasRole>[1];
}

/** The phone's tabs hold the first three; the rest are on its "More" page. */
export const TAB_LINKS: NavLink[] = [
  { to: "/", label: "nav.today", icon: House, exact: true },
  { to: "/orders", label: "nav.orders", icon: ReceiptText },
  { to: "/products", label: "nav.products", icon: Package },
];

export const WORK_LINKS: NavLink[] = [
  { to: "/customers", label: "customers.title", icon: Users },
  { to: "/cod", label: "cod.title", icon: Banknote },
  { to: "/reports", label: "reports.title", icon: ChartColumn },
];

export const MANAGE_LINKS: NavLink[] = [
  { to: "/billing", label: "billing.title", icon: CreditCard, role: "MANAGER" },
  { to: "/settings/business", label: "business.title", icon: Store, role: "MANAGER" },
  { to: "/settings/categories", label: "categories.title", icon: Tags, role: "MANAGER" },
  { to: "/settings/delivery", label: "delivery.title", icon: Truck, role: "MANAGER" },
  { to: "/settings/staff", label: "staff.title", icon: UserPlus, role: "MANAGER" },
  { to: "/settings/audit-log", label: "audit.title", icon: ScrollText, role: "OWNER" },
];
