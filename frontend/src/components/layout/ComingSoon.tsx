import { useTranslation } from "react-i18next";
import { PageHeader } from "./PageHeader";

/** A screen whose phase hasn't been built yet (frontend-plan.md, F1 to F11). */
export function ComingSoon({ title }: { title: string }) {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={title} />
      <p className="text-muted-foreground">{t("page.comingSoon")}</p>
    </>
  );
}
