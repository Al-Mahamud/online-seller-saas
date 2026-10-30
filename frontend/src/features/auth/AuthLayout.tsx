import { Outlet } from "@tanstack/react-router";
import { Brand } from "@/components/layout/Brand";
import { LanguageToggle } from "@/components/layout/LanguageToggle";

/** The frame of the signing-in pages: one column, as in the mockup. */
export function AuthLayout() {
  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-md flex-col gap-6 px-5.5 pt-5 pb-7">
      <header className="flex items-center justify-between">
        <Brand large />
        <LanguageToggle />
      </header>
      <main className="flex flex-1 flex-col gap-5 pt-4">
        <Outlet />
      </main>
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
