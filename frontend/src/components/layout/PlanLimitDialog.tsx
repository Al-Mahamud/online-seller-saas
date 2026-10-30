import { Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
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
import { listenForPlanLimit, type PlanLimit } from "./planLimit";

/** Mounted once, in the app's frame: what the limit is, and the way to a bigger plan. */
export function PlanLimitDialog() {
  const { t, i18n } = useTranslation();
  const format = useFormat();
  const [limit, setLimit] = useState<PlanLimit>();
  useEffect(() => listenForPlanLimit(setLimit), []);
  if (!limit) {
    return null;
  }
  const close = () => setLimit(undefined);
  const key = `planLimit.metric.${limit.metric}`;
  const what = i18n.exists(key) ? t(key as never) : t("planLimit.metric.other");
  return (
    <Dialog open onOpenChange={(open) => !open && close()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("planLimit.title")}</DialogTitle>
          <DialogDescription>
            {limit.limit === null
              ? t("planLimit.reached", { what })
              : t("planLimit.reachedAt", { what, limit: format.number(limit.limit) })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={close}>
            {t("page.close")}
          </Button>
          <Button asChild onClick={close}>
            <Link to="/billing">{t("planLimit.seePlans")}</Link>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
