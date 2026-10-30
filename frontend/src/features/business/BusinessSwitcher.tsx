import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { Check, ChevronsUpDown, Plus } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { businessesQuery, businessSelected, initials, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { CreateBusinessForm } from "./CreateBusinessForm";

/** The logo, or the first letters of the name. */
function BusinessMark({ logo, name }: { logo: string | undefined; name: string }) {
  return logo ? (
    <img src={logo} alt="" className="size-8 shrink-0 rounded-lg object-cover" />
  ) : (
    <span
      aria-hidden="true"
      className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-sm font-bold text-primary-foreground"
    >
      {initials(name)}
    </span>
  );
}

/** Which business the app is working in, and the way to another, in the header as in the mockup. */
export function BusinessSwitcher({ wide = false }: { wide?: boolean }) {
  const { t } = useTranslation();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const router = useRouter();
  const [open, setOpen] = useState(false);
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
        <DropdownMenuTrigger
          className={cn(
            "flex min-h-11 min-w-0 cursor-pointer items-center gap-2 rounded-lg text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
            wide && "w-full border p-2",
          )}
        >
          <BusinessMark logo={business.logo?.small} name={business.name} />
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate font-semibold">
              <span className="sr-only">{t("business.switch")}: </span>
              {business.name}
            </span>
            {wide && <span className="text-[13px] text-muted-foreground">{t(`business.role.${business.role}`)}</span>}
          </span>
          <ChevronsUpDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
        </DropdownMenuTrigger>
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
        <Dialog open onOpenChange={(next) => !next && setCreating(false)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t("business.new")}</DialogTitle>
            </DialogHeader>
            <CreateBusinessForm onCreated={() => setCreating(false)} />
          </DialogContent>
        </Dialog>
      )}
    </>
  );
}
