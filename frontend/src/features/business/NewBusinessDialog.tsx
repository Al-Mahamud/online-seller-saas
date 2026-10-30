import { useTranslation } from "react-i18next";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { CreateBusinessForm } from "./CreateBusinessForm";

export default function NewBusinessDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("business.new")}</DialogTitle>
        </DialogHeader>
        <CreateBusinessForm onCreated={onClose} />
      </DialogContent>
    </Dialog>
  );
}
