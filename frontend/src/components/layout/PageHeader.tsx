import { Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

/** Back to the More page, from the screens under it. */
export function BackToMore() {
  const { t } = useTranslation();
  return (
    <Link to="/more" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline md:hidden">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("nav.more")}
    </Link>
  );
}

export function PageHeader({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{title}</h1>
      {children}
    </div>
  );
}
