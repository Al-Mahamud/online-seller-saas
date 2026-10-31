import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { Check, Plus } from "lucide-react";
import { lazy, type ReactNode, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { businessesQuery, businessSelected } from "@/api/business";
import { api, call } from "@/api/client";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

// With the form libraries it brings, this is only needed when creating one.
const NewBusinessDialog = lazy(() => import("./NewBusinessDialog"));

/** The businesses to choose from, opened by the switcher's own button (`children`, with its look in `className`). */
export default function BusinessMenu({ className, children }: { className: string; children: ReactNode }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [open, setOpen] = useState(true);
  const [creating, setCreating] = useState(false);
  const { data: businesses } = useQuery({ ...businessesQuery, enabled: open });
  const switchTo = useMutation({
    mutationFn: (businessId: string) => call(api.POST("/api/v1/me/switch-business", { body: { businessId } })),
    onSuccess: async (selected) => {
      businessSelected(queryClient, selected);
      await router.navigate({ to: "/" });
      await router.invalidate();
    },
  });

  return (
    <>
      <DropdownMenu modal={false} open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger className={className}>{children}</DropdownMenuTrigger>
        <DropdownMenuContent className="w-72 max-w-[calc(100vw-2rem)]">
          <DropdownMenuLabel>{t("business.switch")}</DropdownMenuLabel>
          {businesses?.map((item) => (
            <DropdownMenuItem
              key={item.id}
              className="min-h-11"
              disabled={switchTo.isPending}
              onSelect={() => {
                if (!item.selected) {
                  switchTo.mutate(item.id);
                }
              }}
            >
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate font-medium">{item.name}</span>
                <span className="text-xs text-muted-foreground">{t(`business.role.${item.role}`)}</span>
              </span>
              {item.selected && <Check aria-hidden="true" className="size-4" />}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem className="min-h-11" onSelect={() => setCreating(true)}>
            <Plus aria-hidden="true" />
            {t("business.new")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {creating && (
        <Suspense fallback={null}>
          <NewBusinessDialog onClose={() => setCreating(false)} />
        </Suspense>
      )}
    </>
  );
}
