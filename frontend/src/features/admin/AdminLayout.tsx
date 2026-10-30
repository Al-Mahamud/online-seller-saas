import { useQuery } from "@tanstack/react-query";
import { Link, Outlet } from "@tanstack/react-router";
import { useMe } from "@/api/session";
import { Brand } from "@/components/layout/Brand";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { Button } from "@/components/ui/button";
import { useSignOut } from "@/features/account/useSignOut";
import { metricsQuery } from "./api";
import { useAdminTexts } from "./texts";

/**
 * The platform's own control room, apart from the shop screens: an admin may run no shop at all. The
 * server decides who gets in, so this asks it, and shows no data until it agrees.
 */
export function AdminLayout() {
  const text = useAdminTexts();
  const access = useQuery(metricsQuery);
  const links = [
    { to: "/admin", label: text.nav.businesses, exact: true },
    { to: "/admin/invoices", label: text.nav.invoices },
    { to: "/admin/plans", label: text.nav.plans },
    { to: "/admin/metrics", label: text.nav.metrics },
    { to: "/admin/audit", label: text.nav.audit },
  ] as const;
  return (
    <div className="min-h-dvh">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b bg-card px-4 py-1.5 md:px-10">
        <span className="flex items-center gap-2">
          <Brand />
          <span className="rounded-full bg-foreground px-2.5 text-[13px] font-semibold text-background">
            {text.title}
          </span>
        </span>
        <span className="flex items-center gap-1">
          <Link to="/" className="flex min-h-11 items-center px-2 text-primary">
            {text.backToApp}
          </Link>
          <LanguageToggle />
        </span>
      </header>
      {access.isError && <Gate />}
      {access.isSuccess && (
        <>
          <nav aria-label={text.title} className="flex gap-1 overflow-x-auto border-b bg-card px-4 md:px-10">
            {links.map((link) => (
              <Link
                key={link.to}
                to={link.to}
                activeOptions={{ exact: "exact" in link }}
                className="flex min-h-11 flex-none items-center border-b-2 border-transparent px-3 text-foreground no-underline data-[status=active]:border-primary data-[status=active]:font-semibold"
              >
                {link.label}
              </Link>
            ))}
          </nav>
          <main className="px-4 pt-4 pb-12 md:px-10 md:pt-6">
            <Outlet />
          </main>
        </>
      )}
    </div>
  );
}

/** Refused: two-step sign-in is off, or this session began before the rights were given. */
function Gate() {
  const text = useAdminTexts();
  const me = useMe();
  const signOut = useSignOut();
  return (
    <main className="flex max-w-xl flex-col items-start gap-3 px-4 pt-6 md:px-10">
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
    </main>
  );
}
