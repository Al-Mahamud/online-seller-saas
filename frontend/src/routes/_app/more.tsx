import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useBusiness } from "@/api/business";
import { linkAllowed, MANAGE_LINKS, type NavLink, WORK_LINKS } from "@/components/layout/navLinks";

export const Route = createFileRoute("/_app/more")({
  // For phones: from a laptop up, all of this is in the sidebar.
  beforeLoad: () => {
    if (window.matchMedia("(min-width: 768px)").matches) {
      throw redirect({ to: "/", replace: true });
    }
  },
  component: More,
});

/** Everything that isn't a tab. The account's things are behind the business's name, at the top. */
function More() {
  const { t } = useTranslation();
  const business = useBusiness();
  const manage = MANAGE_LINKS.filter((link) => linkAllowed(link, business));
  const work = WORK_LINKS.filter((link) => linkAllowed(link, business));
  return (
    <div className="flex w-full flex-col gap-4">
      <div className="relative -mx-4 -mt-4 bg-ledger pt-3 pr-4.5 pb-10.5 pl-6.5 text-white">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold" />
        <h1 className="text-[28px] leading-tight font-bold">{t("nav.more")}</h1>
      </div>
      <div className="relative -mx-4 -mt-11.5 flex flex-col gap-3 rounded-t-3xl bg-background px-3.5 pt-3.5">
        <MoreLinks label={t("nav.work")} links={work} />
        {manage.length > 0 && (
          <>
            <h2 className="mt-0.5 -mb-1.5 ml-1 text-sm font-semibold text-muted-foreground">{t("nav.manage")}</h2>
            <MoreLinks label={t("nav.manage")} links={manage} />
          </>
        )}
      </div>
    </div>
  );
}

function MoreLinks({ label, links }: { label: string; links: NavLink[] }) {
  const { t } = useTranslation();
  return (
    <nav aria-label={label} className="flex flex-col rounded-[14px] border bg-card px-3.5 py-0.5">
      {links.map((link) => (
        <Link
          key={link.label}
          to={link.to}
          className="flex min-h-12.5 items-center gap-3 border-t text-foreground no-underline first:border-t-0 hover:text-foreground"
        >
          <link.icon aria-hidden="true" className="size-5 flex-none text-primary" />
          <span className="flex-1">{t(link.label)}</span>
          <ChevronRight aria-hidden="true" className="size-4.5 flex-none text-muted-foreground" />
        </Link>
      ))}
    </nav>
  );
}
