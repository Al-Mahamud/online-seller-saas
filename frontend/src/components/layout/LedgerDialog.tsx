import { X } from "lucide-react";
import { type ReactNode, useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";

/**
 * A form in a dialog with the dark header, a body that scrolls, and a footer that says what saving will
 * do. It takes the whole screen on phones. After a refused save, the problem or the first wrong field
 * is brought into view.
 */
export function LedgerDialog({
  title,
  description,
  problem,
  summary,
  figures,
  action,
  pending,
  cancelOnPhone = true,
  onSubmit,
  onClose,
  children,
}: {
  title: string;
  description: string;
  problem?: string;
  /** What saving will do, when there's more to say than the button does. */
  summary?: string;
  /** Running totals of what's being entered, under the title. */
  figures?: { label: string; value: string }[];
  action: string;
  pending: boolean;
  /** A screen with little to lose can leave closing to the × on phones, and give the action the whole row. */
  cancelOnPhone?: boolean;
  onSubmit: () => void;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const form = useRef<HTMLFormElement>(null);
  const problemBox = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (problem) {
      problemBox.current?.scrollIntoView({ block: "nearest" });
    }
  }, [problem]);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="flex max-h-[calc(100dvh-2rem)] flex-col gap-0 overflow-hidden p-0 text-base max-md:h-dvh max-md:max-h-dvh max-md:max-w-full max-md:rounded-none sm:max-w-[680px]"
      >
        <header className="relative flex flex-none items-start justify-between gap-3 bg-ledger py-3 pr-2.5 pl-6.5 text-white md:py-4 md:pr-4 md:pl-12">
          <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-6" />
          <div className="min-w-0">
            <DialogTitle className="text-2xl leading-snug font-bold">{title}</DialogTitle>
            <DialogDescription className="text-[15px] break-words text-ledger-text">{description}</DialogDescription>
            {figures && figures.length > 0 && (
              <dl className="mt-2.5 flex flex-wrap gap-x-7 gap-y-1.5 border-t border-ledger-outline pt-2.5">
                {figures.map((figure, index) => (
                  <div key={figure.label}>
                    <dt className="text-sm font-semibold text-ledger-text">{figure.label}</dt>
                    <dd className={cn("text-[22px] leading-tight font-bold", index === 0 && "text-marigold-text")}>
                      {figure.value}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </div>
          <DialogClose asChild>
            <Button
              variant="ghost"
              size="icon"
              className="size-11 flex-none text-white hover:bg-white/10 hover:text-white focus-visible:ring-marigold-text"
            >
              <X aria-hidden="true" className="size-5" />
              <span className="sr-only">{t("page.close")}</span>
            </Button>
          </DialogClose>
        </header>
        <form
          ref={form}
          noValidate
          className="flex min-h-0 flex-1 flex-col"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
            // After the errors have drawn: a wrong field may have scrolled out of sight.
            requestAnimationFrame(() => form.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
          }}
        >
          <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4 md:px-6 md:py-4.5">
            {problem && (
              <p
                ref={problemBox}
                role="alert"
                className="rounded-[10px] bg-danger-soft px-3.5 py-2.75 text-[15px] font-semibold text-danger-ink"
              >
                {problem}
              </p>
            )}
            {children}
          </div>

          <footer className="flex flex-none flex-col gap-2.5 border-t bg-background px-4 pt-3 pb-4 md:flex-row md:flex-wrap md:items-center md:justify-between md:gap-x-4 md:px-6 md:py-3.5">
            {summary && (
              <p role="status" className="text-[15px] font-semibold text-[#2B342F]">
                {summary}
              </p>
            )}
            <div className="flex gap-2.5 md:ml-auto">
              <DialogClose asChild>
                <Button
                  variant="outline"
                  className={cn(
                    "h-13 flex-1 rounded-[10px] border-[1.5px] text-base font-semibold md:h-11.5 md:flex-none",
                    !cancelOnPhone && "max-md:hidden",
                  )}
                >
                  {t("page.cancel")}
                </Button>
              </DialogClose>
              <Button
                type="submit"
                className="h-13 flex-[2] rounded-[10px] text-lg font-semibold md:h-11.5 md:flex-none md:px-5.5 md:text-base"
                disabled={pending}
              >
                {action}
              </Button>
            </div>
          </footer>
        </form>
      </DialogContent>
    </Dialog>
  );
}
