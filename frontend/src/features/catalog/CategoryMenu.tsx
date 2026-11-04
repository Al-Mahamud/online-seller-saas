import { Ellipsis } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { MENU_TRIGGER, type MenuAction } from "./categoryMenuParts";

/**
 * A category's ⋯ menu. Loaded on the first tap, already open: the menu library is only needed by
 * someone who opens one, so the page itself stays light.
 */
export default function CategoryMenu({ label, actions }: { label: string; actions: MenuAction[] }) {
  const safe = actions.filter((action) => !action.danger);
  const danger = actions.filter((action) => action.danger);
  return (
    <DropdownMenu modal={false} defaultOpen>
      <DropdownMenuTrigger aria-label={label} className={MENU_TRIGGER}>
        <Ellipsis aria-hidden="true" className="size-4.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60 p-1.5">
        {safe.map((action) => (
          <DropdownMenuItem key={action.label} className="min-h-11 text-[15px]" onSelect={action.onSelect}>
            {action.label}
          </DropdownMenuItem>
        ))}
        {danger.length > 0 && <DropdownMenuSeparator />}
        {danger.map((action) => (
          <DropdownMenuItem
            key={action.label}
            className={cn(
              "min-h-11 flex-col items-start justify-center gap-0 text-[15px] font-bold text-destructive focus:text-destructive",
            )}
            onSelect={action.onSelect}
          >
            {action.label}
            {action.hint && <span className="text-[13px] font-normal text-muted-foreground">{action.hint}</span>}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
