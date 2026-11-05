import { useQuery } from "@tanstack/react-query";
import { Link, Outlet, useRouterState } from "@tanstack/react-router";
import { ChevronLeft, Landmark, Layers, LayoutDashboard, List, LogOut, Menu, Store, X } from "lucide-react";
import { useId, useState } from "react";
import { useMe } from "@/api/session";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { metricsQuery } from "./api";
import { useAdminTexts } from "./texts";

const SIDE_LINK =
  "flex min-h-11 flex-none items-center gap-2.75 rounded-[10px] px-2.5 text-[15.5px] text-white/90 no-underline hover:bg-white/8 hover:text-white focus-visible:outline-3 focus-visible:-outline-offset-3 focus-visible:outline-marigold-text data-[status=active]:bg-white/14 data-[status=active]:font-bold data-[status=active]:text-white [&_svg]:size-4.75 [&_svg]:flex-none [&_svg]:text-ledger-text data-[status=active]:[&_svg]:text-marigold-text";

function Brand() {
  const text = useAdminTexts();
  return (
    <span className="flex items-center gap-2.5">
      <span
        aria-hidden="true"
        className="flex size-9 flex-none items-center justify-center rounded-[10px] bg-marigold-text text-lg font-bold text-ledger"
      >
        {text.brand.slice(0, 1)}
      </span>
      <span className="text-xl font-bold">{text.brand}</span>
      <span className="rounded-full border-[1.5px] border-marigold-text px-2.5 text-[13px] leading-5.5 font-bold text-marigold-text">
        {text.title}
      </span>
    </span>
  );
}

/** The admin area's pages, with the count of bank transfers waiting beside theirs. */
function AdminLinks({ onPick }: { onPick?: () => void }) {
  const text = useAdminTexts();
  const format = useFormat();
  const { data } = useQuery(metricsQuery);
  const waiting = data?.waitingTransfers ?? 0;
  return (
    <>
      <Link to="/admin" activeOptions={{ exact: true }} className={SIDE_LINK} onClick={onPick}>
        <LayoutDashboard aria-hidden="true" />
        {text.nav.overview}
      </Link>
      <Link to="/admin/businesses" className={SIDE_LINK} onClick={onPick}>
        <Store aria-hidden="true" />
        {text.nav.businesses}
      </Link>
      <Link to="/admin/invoices" className={SIDE_LINK} onClick={onPick}>
        <Landmark aria-hidden="true" />
        <span className="flex-1">{text.nav.invoices}</span>
        {waiting > 0 && (
          <span className="flex h-5.5 min-w-5.5 items-center justify-center rounded-full bg-marigold-text px-1.5 text-[13px] font-bold text-ledger">
            {format.number(waiting)}
          </span>
        )}
      </Link>
      <Link to="/admin/plans" className={SIDE_LINK} onClick={onPick}>
        <Layers aria-hidden="true" />
        {text.nav.plans}
      </Link>
      <Link to="/admin/audit" className={SIDE_LINK} onClick={onPick}>
        <List aria-hidden="true" />
        {text.nav.audit}
      </Link>
    </>
  );
}

/** Back to the shop, the language, and who is signed in. */
function AdminFooter({ onPick }: { onPick?: () => void }) {
  const text = useAdminTexts();
  const me = useMe();
  const signOut = useSignOut();
  return (
    <div className="flex flex-col gap-0.5 border-t border-ledger-outline pt-2.5">
      <Link to="/" className={SIDE_LINK} onClick={onPick}>
        <ChevronLeft aria-hidden="true" />
        {text.backToApp}
      </Link>
      <LanguageToggle className="self-start text-marigold-text hover:bg-white/8 hover:text-marigold-text" />
      <div className="mt-1 flex items-center gap-2.5 rounded-xl bg-white/7 p-2.5">
        <span
          aria-hidden="true"
          className="flex size-8.5 flex-none items-center justify-center rounded-full bg-accent text-sm font-bold text-accent-foreground"
        >
          {me.name.slice(0, 1)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-bold">{me.name}</span>
          <span className="block text-[13px] text-ledger-text">{text.role}</span>
        </span>
        <button
          type="button"
          aria-label={text.signOut}
          disabled={signOut.isPending}
          onClick={() => signOut.mutate()}
          className="flex size-10 flex-none cursor-pointer items-center justify-center rounded-[10px] text-ledger-text hover:bg-white/8 hover:text-white"
        >
          <LogOut aria-hidden="true" className="size-4.5" />
        </button>
      </div>
    </div>
  );
}

/**
 * The platform's own control room, apart from the shop screens: an admin may run no shop at all. The
 * server decides who gets in, so this asks it, and shows no data until it agrees.
 */
export function AdminLayout() {
  const text = useAdminTexts();
  const access = useQuery(metricsQuery);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const path = useRouterState({ select: (state) => state.location.pathname });
  // A page picked elsewhere (Back, a link in the page) closes the phone's menu too.
  const [shownPath, setShownPath] = useState(path);
  if (shownPath !== path) {
    setShownPath(path);
    setMenuOpen(false);
  }
  const allowed = access.isSuccess;
  return (
    <div className="min-h-dvh bg-background md:flex">
      {allowed && (
        <aside className="sticky top-0 hidden h-dvh w-62 shrink-0 flex-col gap-4 bg-ledger px-3 py-4.5 text-white md:flex">
          <div className="px-2.5 pt-1 pb-1.5">
            <Brand />
          </div>
          <nav aria-label={text.title} className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto">
            <AdminLinks />
          </nav>
          <AdminFooter />
        </aside>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header
          className={cn(
            "sticky top-0 z-10 flex flex-col bg-ledger px-4 text-white",
            allowed ? "md:hidden" : "md:px-10",
          )}
        >
          <div className="flex min-h-14 items-center justify-between gap-2">
            <Brand />
            {allowed ? (
              <button
                type="button"
                aria-label={text.menu}
                aria-expanded={menuOpen}
                aria-controls={menuId}
                onClick={() => setMenuOpen((open) => !open)}
                className="flex size-11 cursor-pointer items-center justify-center rounded-xl bg-white/10"
              >
                {menuOpen ? (
                  <X aria-hidden="true" className="size-5.5" />
                ) : (
                  <Menu aria-hidden="true" className="size-5.5" />
                )}
              </button>
            ) : (
              <Link to="/" className="flex min-h-11 items-center text-marigold-text">
                {text.backToApp}
              </Link>
            )}
          </div>
          {allowed && menuOpen && (
            <div id={menuId} className="flex flex-col gap-2 pb-3">
              <nav aria-label={text.title} className="flex flex-col gap-0.5">
                <AdminLinks onPick={() => setMenuOpen(false)} />
              </nav>
              <AdminFooter onPick={() => setMenuOpen(false)} />
            </div>
          )}
        </header>
        <main className="flex-1 px-3 pt-3 pb-12 md:px-8 md:pt-7">
          {access.isError && <Gate />}
          {allowed && <Outlet />}
        </main>
      </div>
    </div>
  );
}

/** Refused: two-step sign-in is off, or this session began before the rights were given. */
function Gate() {
  const text = useAdminTexts();
  const me = useMe();
  const signOut = useSignOut();
  return (
    <div className="flex max-w-xl flex-col items-start gap-3 px-1 pt-4 md:px-2">
      <h1 className="text-2xl font-bold tracking-tight">{text.gate.title}</h1>
      <p>{me.twoFactorEnabled ? text.gate.signInAgain : text.gate.off}</p>
      {me.twoFactorEnabled ? (
        <Button disabled={signOut.isPending} onClick={() => signOut.mutate()}>
          {text.gate.signOut}
        </Button>
      ) : (
        <Button asChild>
          <Link to="/settings/security">{text.gate.turnOn}</Link>
        </Button>
      )}
    </div>
  );
}
