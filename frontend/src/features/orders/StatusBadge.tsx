import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { OrderStatus } from "./search";

const STYLES: Record<OrderStatus, string> = {
  DRAFT: "bg-muted text-foreground",
  CONFIRMED: "bg-accent text-accent-foreground",
  PACKED: "bg-accent text-accent-foreground",
  SHIPPED: "bg-accent text-accent-foreground",
  DELIVERED: "bg-primary text-primary-foreground",
  RETURNED: "bg-warning text-warning-foreground",
  CANCELLED: "bg-muted text-muted-foreground",
};

export function StatusBadge({ status }: { status: OrderStatus }) {
  const { t } = useTranslation();
  return (
    <span className={cn("inline-block rounded-full px-2.5 text-[13px] font-semibold", STYLES[status])}>
      {t(`orderStatus.${status}`)}
    </span>
  );
}
