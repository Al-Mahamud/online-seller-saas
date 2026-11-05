import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/** A small form in a dialog: what to do, what the server said if it refused, and the button. */
export function AdminDialog({
  title,
  description,
  action,
  danger,
  problem,
  pending,
  onSubmit,
  onClose,
  children,
}: {
  title: string;
  description?: string;
  action: string;
  /** The action can't simply be undone, such as suspending: a red button. */
  danger?: boolean;
  problem?: string;
  pending: boolean;
  onSubmit: () => void;
  onClose: () => void;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="flex flex-col gap-4">
          {problem && (
            <Alert variant="destructive">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
          {children}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("page.cancel")}
          </Button>
          <Button
            className={danger ? "bg-destructive text-white hover:bg-destructive/90" : undefined}
            disabled={pending}
            onClick={onSubmit}
          >
            {action}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
