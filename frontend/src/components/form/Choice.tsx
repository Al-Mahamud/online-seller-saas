import { cn } from "@/lib/utils";

/** One of a few buttons that pick how something is shown, on a light ground. `on` is announced as pressed. */
export function Choice({
  on,
  onClick,
  className,
  children,
}: {
  on: boolean;
  onClick: () => void;
  className?: string;
  children: string;
}) {
  return (
    <button
      type="button"
      aria-pressed={on}
      className={cn(
        "h-11 flex-none cursor-pointer rounded-[10px] border-[1.5px] px-3.5 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring",
        on
          ? "border-primary bg-primary text-primary-foreground"
          : "border-input bg-card text-foreground hover:bg-muted",
        className,
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
