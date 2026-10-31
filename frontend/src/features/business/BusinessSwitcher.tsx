import { ChevronsUpDown } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { initials, useBusiness } from "@/api/business";
import { cn } from "@/lib/utils";

// The menu's library is the heaviest thing in the app's frame, and most visits never open it.
const loadMenu = () => import("./BusinessMenu");
const BusinessMenu = lazy(loadMenu);

/** The logo, or the first letters of the name. */
function BusinessMark({ logo, name }: { logo: string | undefined; name: string }) {
  return logo ? (
    <img src={logo} alt="" className="size-8 shrink-0 rounded-lg object-cover" />
  ) : (
    <span
      aria-hidden="true"
      className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-sm font-bold text-primary-foreground"
    >
      {initials(name)}
    </span>
  );
}

/** Which business the app is working in, and the way to another, in the header as in the mockup. */
export function BusinessSwitcher({ wide = false }: { wide?: boolean }) {
  const { t } = useTranslation();
  const business = useBusiness();
  const [menu, setMenu] = useState(false);
  const className = cn(
    "flex min-h-11 min-w-0 cursor-pointer items-center gap-2 rounded-lg text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
    wide && "w-full border p-2",
  );
  const face = (
    <>
      <BusinessMark logo={business.logo?.small} name={business.name} />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate font-semibold">
          <span className="sr-only">{t("business.switch")}: </span>
          {business.name}
        </span>
        {wide && <span className="text-[13px] text-muted-foreground">{t(`business.role.${business.role}`)}</span>}
      </span>
      <ChevronsUpDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
    </>
  );
  const button = (
    <button
      type="button"
      className={className}
      onPointerEnter={() => void loadMenu()}
      onFocus={() => void loadMenu()}
      onClick={() => setMenu(true)}
    >
      {face}
    </button>
  );
  return menu ? (
    <Suspense fallback={button}>
      <BusinessMenu className={className}>{face}</BusinessMenu>
    </Suspense>
  ) : (
    button
  );
}
