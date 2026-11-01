import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouter } from "@tanstack/react-router";
import { Check, Download, LogOut, Plus, Shield, User } from "lucide-react";
import { lazy, type ReactNode, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { businessesQuery, businessSelected, initials } from "@/api/business";
import { api, call } from "@/api/client";
import { useMe } from "@/api/session";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useSignOut } from "@/features/account/useSignOut";
import { useFormat } from "@/lib/format";
import { useInstallApp } from "@/lib/install";

// With the form libraries it brings, this is only needed when creating one.
const NewBusinessDialog = lazy(() => import("./NewBusinessDialog"));

const LINK_ITEM = "min-h-11 text-[15px] text-foreground no-underline hover:text-foreground";

/** The person's account and the businesses to choose from, opened by the switcher's own button (`children`, with its look in `className`). */
export default function BusinessMenu({ className, children }: { className: string; children: ReactNode }) {
  const { t } = useTranslation();
  const format = useFormat();
  const me = useMe();
  const signOut = useSignOut();
  const install = useInstallApp();
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
        <DropdownMenuContent align="start" className="w-75 max-w-[calc(100vw-1.5rem)] p-1.5">
          <div className="flex items-center gap-2.5 px-2.5 py-2 text-foreground">
            <span
              aria-hidden="true"
              className="flex size-10 flex-none items-center justify-center rounded-full bg-accent font-bold text-accent-foreground"
            >
              {initials(me.name)}
            </span>
            <span className="min-w-0">
              <span className="block truncate text-base font-bold">{me.name}</span>
              <span className="block truncate text-sm text-muted-foreground">
                {me.email ?? (me.phone && format.phone(me.phone))}
              </span>
            </span>
          </div>
          <DropdownMenuItem
            asChild
            className="min-h-11 bg-accent text-base font-bold text-accent-foreground no-underline hover:text-accent-foreground"
          >
            <Link to="/settings/account">
              <User aria-hidden="true" className="text-accent-foreground" />
              {t("account.title")}
            </Link>
          </DropdownMenuItem>
          {me.platformAdmin && (
            <DropdownMenuItem asChild className={LINK_ITEM}>
              <Link to="/admin">
                <Shield aria-hidden="true" />
                {t("account.admin")}
              </Link>
            </DropdownMenuItem>
          )}
          {install && (
            <DropdownMenuItem className="min-h-11 text-[15px]" onSelect={install}>
              <Download aria-hidden="true" />
              {t("account.install")}
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
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
          <DropdownMenuSeparator />
          <DropdownMenuItem
            className="min-h-11 text-[15px]"
            disabled={signOut.isPending}
            onSelect={() => signOut.mutate()}
          >
            <LogOut aria-hidden="true" />
            {t("account.signOut")}
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
