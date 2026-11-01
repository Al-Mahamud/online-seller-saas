import type { ReactNode } from "react";
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

/** "Are you sure?" before something hard to undo. */
export function ConfirmDialog({
  title,
  description,
  problem,
  confirm,
  pending,
  onConfirm,
  onClose,
}: {
  title: string;
  description: ReactNode;
  /** Why it was refused, when the dialog stays open to try again. */
  problem?: string;
  confirm: string;
  pending: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {problem && (
          <p
            role="alert"
            className="rounded-[10px] bg-danger-soft px-3.5 py-2.75 text-[15px] font-semibold text-danger-ink"
          >
            {problem}
          </p>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("page.cancel")}
          </Button>
          <Button variant="destructive" disabled={pending} onClick={onConfirm}>
            {confirm}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
