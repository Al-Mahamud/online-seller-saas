import { Link, Outlet } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Brand } from "@/components/layout/Brand";
import { LanguageToggle } from "@/components/layout/LanguageToggle";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";

/** The frame of the other pages without tabs, such as the first run: one column. */
export function SimpleFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-md flex-col gap-6 px-5.5 pt-5 pb-7">
      <header className="flex items-center justify-between">
        <Brand large />
        <LanguageToggle />
      </header>
      <main className="flex flex-1 flex-col gap-5 pt-4">{children}</main>
    </div>
  );
}

export function AuthHeading({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <h1 className="text-[28px] leading-snug font-bold">{title}</h1>
      {children && <p className="text-muted-foreground">{children}</p>}
    </div>
  );
}

/** The ledger beside the form on a laptop: what the app keeps, as numbered rows. */
function LedgerPanel() {
  const { t } = useTranslation();
  const format = useFormat();
  const rows = [
    { title: t("auth.panel.stock"), note: t("auth.panel.stockNote") },
    { title: t("auth.panel.orders"), note: t("auth.panel.ordersNote") },
    { title: t("auth.panel.delivery"), note: t("auth.panel.deliveryNote") },
    { title: t("auth.panel.cod"), note: t("auth.panel.codNote") },
  ];
  return (
    <aside className="relative hidden min-w-0 flex-[4_1_0%] flex-col justify-between gap-10 overflow-hidden bg-ledger text-white min-[880px]:flex">
      <div aria-hidden="true" className="absolute inset-y-0 left-18 w-0.5 bg-marigold" />
      <div className="pt-9 pr-14 pl-24">
        <Brand large onDark />
      </div>
      <div className="flex flex-col">
        <p className="mb-10 pr-14 pl-24 text-[44px] leading-[1.28] font-bold text-balance">
          {t("auth.panel.headline")}
        </p>
        <div className="mb-12 flex flex-col border-b border-ledger-line">
          {rows.map((row, index) => (
            <div key={row.title} className="flex min-h-18 items-center border-t border-ledger-line">
              <span
                aria-hidden="true"
                className="flex-[0_0_4.5rem] text-center text-[17px] font-semibold text-marigold-text"
              >
                {format.number(index + 1)}
              </span>
              <div className="py-2 pr-14 pl-6">
                <p className="text-lg font-semibold">{row.title}</p>
                <p className="text-[15px] text-ledger-text">{row.note}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </aside>
  );
}

/** Signing in and up: a dark header over a white sheet on phones, the ledger beside the form on a laptop. */
export function AuthLayout() {
  return (
    <div className="relative flex min-h-dvh bg-ledger min-[880px]:bg-card">
      <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold min-[880px]:hidden" />
      <LedgerPanel />
      <div className="relative flex min-w-0 flex-[5_1_0%] flex-col">
        <header className="flex items-center justify-between gap-3 px-5.5 pt-4 min-[880px]:justify-end min-[880px]:px-8 min-[880px]:pt-6">
          <span className="min-[880px]:hidden">
            <Brand large onDark />
          </span>
          <LanguageToggle className="border-[1.5px] border-ledger-outline text-white hover:bg-white/10 hover:text-white min-[880px]:border-border min-[880px]:bg-card min-[880px]:text-primary min-[880px]:hover:bg-muted min-[880px]:hover:text-accent-foreground" />
        </header>
        <Outlet />
      </div>
    </div>
  );
}

/**
 * One page of signing in or up. One heading for both sizes: over the sheet on phones, in dark green
 * above the form on a laptop.
 */
export function AuthPage({
  title,
  description,
  wide = false,
  children,
}: {
  title: string;
  description?: string;
  wide?: boolean;
  children: React.ReactNode;
}) {
  return (
    <main className="flex flex-1 flex-col min-[880px]:items-center min-[880px]:justify-center min-[880px]:px-6 min-[880px]:pt-4 min-[880px]:pb-10">
      <div
        className={cn(
          "flex w-full flex-1 flex-col min-[880px]:flex-none",
          wide ? "min-[880px]:max-w-110" : "min-[880px]:max-w-100",
        )}
      >
        <div className="flex flex-col gap-1 px-5.5 pt-5 pb-7 text-white min-[880px]:gap-1.5 min-[880px]:px-0 min-[880px]:pt-0 min-[880px]:text-foreground">
          <h1 className="text-[30px] leading-[1.3] font-bold min-[880px]:text-[34px]">{title}</h1>
          {description && (
            <p className="leading-normal text-ledger-text min-[880px]:text-[17px] min-[880px]:text-muted-foreground">
              {description}
            </p>
          )}
        </div>
        <div className="relative flex flex-1 flex-col gap-4.5 rounded-t-3xl bg-card px-5.5 pt-6.5 pb-6 min-[880px]:flex-none min-[880px]:rounded-none min-[880px]:p-0 [&_[data-slot=input]]:h-13 [&_[data-slot=input]]:rounded-[10px] [&_[data-slot=input]]:border-[1.5px] [&_[data-slot=input]]:text-[17px]">
          {children}
        </div>
      </div>
    </main>
  );
}

/** How far through signing up: a label and two bars. */
export function StepBar({ step }: { step: 1 | 2 }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm font-semibold text-muted-foreground">{t("auth.step", { step: format.number(step) })}</p>
      <div aria-hidden="true" className="grid grid-cols-2 gap-1.5">
        <span className="h-1 rounded-sm bg-primary" />
        <span className={cn("h-1 rounded-sm", step === 2 ? "bg-primary" : "bg-border")} />
      </div>
    </div>
  );
}

/** The main button of these pages. */
export const authButton = "h-13 rounded-[10px] text-lg font-semibold";

/** A way out of a problem, on the line of a field's label. */
export function LabelLink({ to, children }: { to: "/forgot-password" | "/forgot-user-id"; children: React.ReactNode }) {
  return (
    <Button variant="link" className="-my-2.5 px-0 text-[15px] font-semibold" asChild>
      <Link to={to}>{children}</Link>
    </Button>
  );
}

/** Back to signing in, from the recovery pages. */
export function BackToSignIn() {
  const { t } = useTranslation();
  return (
    <Button variant="link" className="self-start px-0 font-semibold" asChild>
      <Link to="/sign-in">
        <ArrowLeft aria-hidden="true" />
        {t("auth.forgot.backToSignIn")}
      </Link>
    </Button>
  );
}
