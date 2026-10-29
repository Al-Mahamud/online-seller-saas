import type { ReactNode } from "react";

export function PageHeader({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{title}</h1>
      {children}
    </div>
  );
}
