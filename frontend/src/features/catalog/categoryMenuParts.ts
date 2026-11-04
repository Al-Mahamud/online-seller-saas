/** What a category's ⋯ menu shares with the page, apart from the menu library (loaded on first use). */
export interface MenuAction {
  label: string;
  /** A second line under it, such as why it can't be done yet. */
  hint?: string;
  danger?: boolean;
  onSelect: () => void;
}

export const MENU_TRIGGER =
  "flex size-10 cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card hover:bg-muted data-[state=open]:border-primary";
