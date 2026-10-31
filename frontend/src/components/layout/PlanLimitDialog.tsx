import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import type { PlanLimit } from "./planLimit";

/** What the limit is, and the way to a bigger plan. */
export default function PlanLimitDialog({ limit, onClose: close }: { limit: PlanLimit; onClose: () => void }) {
  const { t, i18n } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const key = `planLimit.metric.${limit.metric}`;
  const what = i18n.exists(key) ? t(key as never) : t("planLimit.metric.other");
  // These two aren't an amount used up, so "the limit is reached" would be wrong.
  const count = format.number(limit.limit ?? 0);
  let text = limit.limit === null ? t("planLimit.reached", { what }) : t("planLimit.reachedAt", { what, limit: count });
  if (limit.metric === "report_days") {
    text = t("planLimit.reportDays", { limit: count });
  } else if (limit.metric === "exports") {
    text = t("planLimit.noExports");
  }
  return (
    <Dialog open onOpenChange={(open) => !open && close()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("planLimit.title")}</DialogTitle>
          <DialogDescription>
            {text}
            {!hasRole(business, "MANAGER") && ` ${t("planLimit.askOwner")}`}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={close}>
            {t("page.close")}
          </Button>
          {hasRole(business, "MANAGER") && (
            <Button asChild onClick={close}>
              <Link to="/billing">{t("planLimit.seePlans")}</Link>
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
